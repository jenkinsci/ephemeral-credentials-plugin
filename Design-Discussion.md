# Design Discussion: Ephemeral Credentials Provider

This document is a record of the design conversation that led to this plugin
- the questions asked, the paths explored and rejected, and the reasoning
behind the choices that ended up in the code. Kept alongside the plugin so
future changes can be weighed against *why* things are built this way, not
just *what* is built.

## 1. The original question: a build-scoped credential store

**Question:** Is it possible to register a Jenkins credential store during a
build run - one that disappears when the build ends - and populate it
programmatically, so that `withCredentials()` would use it?

**Answer:** Yes, architecturally. The credentials plugin's lookup path
(`CredentialsProvider.findCredentialById`) walks every registered
`CredentialsProvider` extension and asks each "what do you have for this
context?" - nothing requires that to be backed by disk storage. Two
approaches were identified:

1. Add a credential to an *existing* writable store (e.g. a Folder's
   `CredentialsStore`) at runtime, remove it later - uses stock APIs, but
   briefly persists to `credentials.xml` and risks collisions between
   concurrent builds sharing that store.
2. A true zero-persistence provider: a custom `CredentialsProvider` whose
   `getCredentials(...)` recognizes "is this context the specific Run I care
   about" and returns an in-memory credential, registered via
   `Jenkins.get().getExtensionList(CredentialsProvider.class).add(...)` /
   `.remove(...)` around the build.

Constraints noted immediately: this needs to live in a **trusted** context
(the Groovy sandbox blocks `Jenkins.get()`-level extension manipulation from
inline Jenkinsfile code), and removal must be `finally`-guarded so an
exception mid-build doesn't leave a stale registration.

## 2. The real use case: interactive resolution of missing credentials

**Refinement:** The actual goal was a store that, when asked for an ID not
registered anywhere else, interactively requests it (matching the
credential-type UI or via `input`), caches the result for the rest of the
run, and never persists it to disk (not even as CPS/pipeline durability
state, not logged, not saved as build arguments).

This ruled out naive designs immediately: the secret must never sit in an
ordinary CPS-transformed pipeline-script variable across a step boundary,
since Pipeline's durability engine periodically serializes the running
script's own local variables to `program.dat` - a real, non-theoretical leak
path distinct from the classic `build.xml`/`credentials.xml` one.

## 3. First design: a JSL shared-library step + a per-build provider

Initial shape: a JSL `vars/` step wrapping `withCredentials`, backed by an
`EphemeralCredentialsProvider` class in `JSL/src/io/jenkins/plugins/`,
registered/deregistered via `ExtensionList` add/remove around the build.

### Rejected: "hijack" other providers so this store is the only visible one

**Question:** Could the provider remove/replace other registered
`CredentialsProvider`s for the duration of the build, so it's the sole
resolution path, proxying to the originals for IDs they already have?

**Answer:** Mechanically possible (`ExtensionList.remove()`/`.add()` are
real APIs), but rejected. `ExtensionList<CredentialsProvider>` is a single,
JVM-wide list - there is no per-build view of it. Removing the real
providers would make *every other job on the controller* blind to its
credentials for the duration, not just the build doing the hijacking. It
was also unnecessary: `CredentialsProvider.findCredentialById` already
searches every registered provider and merges results, so an additive-only
provider gets the same "existing IDs still resolve, missing ones get
supplied" behavior with none of the blast radius. The "single controlled
path" property the idea was reaching for is better achieved at the calling
step's level (always check `findCredentialById` before deciding to prompt),
not by suppressing other providers from Jenkins' view.

### Rejected (then fixed): `Executor`-based correlation

**Question:** For pipelines with parallel or sequential stages using
different `node`/`agent` blocks, would `Executor`-based correlation
("which build owns the currently running executor") remain stable, or would
each stage re-request the credential because the identifier changed?

**Answer:** No, it would not remain stable, and this was a real problem, not
an edge case:

- A single Pipeline `Run` can hold **multiple** `Executor`s simultaneously
  (one per active `parallel` branch).
- Sequential stages release and reacquire a new `Executor` per `node` block,
  possibly on a different agent; between blocks there may be no current
  `Executor` at all.
- The actual CPS interpreter thread pool (`CpsVmExecutorService`) is a
  JVM-wide, shared pool across *all* running builds - not tied to any
  particular `Executor` or build.

**Fix:** correlate via `CpsThread.current().getExecution().getOwner().getExecutable()`
instead - anchored to the whole-build-lifetime `CpsFlowExecution`, which is
shared by every branch of a `parallel` block and unaffected by agent/stage
changes. This became `CpsRuns.current()` in the final implementation.

### Orthogonality: is the step redundant with the provider?

**Question:** With a new step doing the interactive lookup, don't you still
need to track down and update every existing credential consumer (like
`checkout`) to use it? And isn't a registered provider, by itself,
sufficient?

**Answer:** They're complementary, not redundant. The **provider** is what
makes an ephemeral value discoverable by code nobody touched (any
unmodified `withCredentials`/`checkout` call in the same build). The
**step** is the only thing that decides *when* to interactively prompt and
populate the provider's cache - the provider must stay passive, since
`getCredentials()`-style methods get called incidentally by all sorts of
unrelated code (UI dropdowns, other plugins enumerating what's available),
and firing a prompt from there would trigger on incidental enumeration, not
genuine need.

This also surfaced a real tradeoff: caching under the **literal** requested
ID gives transparency to unmodified callers in the same build, but is only
safe if two concurrent builds of the *same job* can't both be missing that
literal ID at once (since, at the time, the provider could not reliably
tell which of several concurrently-registered instances a given call
belonged to). A **synthesized** ID (`FOO@@runId`) sidesteps that ambiguity
entirely, at the cost of only benefiting callers that go through the
wrapper (since only the wrapper knows to rewrite the ID).

### The singleton problem

**Question:** Is a JSL shared-library class actually a JVM-wide singleton,
or only build-scoped? Jenkins jobs run in different-enough (CPS) contexts
for that to matter, right?

**Answer:** Confirmed: not a singleton at all. Jenkins compiles a shared
library's `src/` classes **per `CpsFlowExecution`** - each build gets its
own classloader and therefore its own `Class` object, with independent
`static` state. Two concurrent builds' "same" class are unrelated types as
far as the JVM is concerned. A dynamically-loaded shared-library class also
could never be picked up by `@Extension` static discovery in the first
place (that scan happens at Jenkins boot, before the library's classes
exist), so manual `ExtensionList.add()/remove()` was always the only
registration mechanism available - this was already the plan, and the
finding reinforced rather than changed it.

**Consequence:** this is precisely why the provider needed to be a real
Jenkins **plugin**, not a JSL class - only a genuine `@Extension` class,
loaded once at Jenkins startup from an installed plugin, gives one shared
instance across all builds.

## 4. Moving to a real plugin

**Question:** Would a real plugin - a genuine `CredentialsProvider`
singleton, with a step for a build to self-register, passing the pipeline
script context so the plugin could call back into `input`/`lock` when
needed - be doable and CPS-safe?

**Answer, in parts:**

- **Determining "which build is asking" inside `getCredentials()`:** yes,
  via `CpsThread.current()` as above - not via call-stack inspection or an
  env-var "cookie" (neither reaches the right place; env vars require
  already having the `Run`, and stack-walking is fragile/slow compared to
  the documented `CpsThread` accessor). Because a real plugin is a genuine
  singleton, and `CpsThread` resolves the actual calling `Run` directly
  (not by decoding an ID string), this also **reconciled** the earlier
  literal-ID-vs-synthesized-ID tradeoff: correctness no longer depended on
  encoding the run into the ID, since the provider could (it was believed
  at the time) resolve the calling run directly and safely even under
  concurrent builds of the same job.
- **Calling `input`/`lock` from the plugin's own Java code, via a stored
  script reference:** rejected. `input` is a pausable, durability-relevant
  step - its whole design is to suspend the CPS continuation and persist
  "waiting on a human" state to disk so a paused build survives a
  controller restart. Invoking it via a stored script reference from plain
  Java code (rather than genuine CPS-interpreted execution) sidesteps that
  scheduling path; it risks blocking a real thread synchronously instead of
  truly pausing, and losing restart-durability for that pause.
- **Resolution:** keep `input`/`lock` inside genuine CPS-interpreted script
  code, and have the plugin's Java side stay limited to passive storage
  (`put`/`find`/`has`) plus lifecycle cleanup - a `RunListener`'s
  `onFinalized`/`onDeleted` as the authoritative backstop, more reliable
  than a pipeline script's own `finally` block, since a hard-killed build
  can skip the latter.

This is the point at which the plugin was actually scaffolded at
`EphemeralCredentialsProvider` directory (initially Gradle, per house
convention for Jenkins shared libraries; later converted to Maven on
request, since Maven is the more common toolchain for Jenkins plugins
specifically).

## 5. Making it transparent without per-call-site migration

**Question:** Needing a JSL step at every credential use (even a smart one)
still defeats transparency for things like `checkout`, which reach
`CredentialsProvider` deep in their own call stack. Could a generic
`withEphemeralCredentials { ... }` wrapper "switch the attention of the
execution context" so lock/input can execute safely, letting existing
unmodified code inside the block just work?

**Answer:** Partially, with an important technical ceiling. `input`/`lock`
can only safely suspend at a genuine CPS-recognized step boundary - the
wrapper's own scope - not literally injected into `checkout`'s internal call
stack. Two mechanisms at that boundary were compared:

- **Catch `CredentialNotFoundException` and retry** - already an
  established pattern in this exact codebase (`JSL/vars/execDBtool.groovy`,
  `SUTLockableDB.groovy`, `SUTPatchapplTestbed.groovy` all catch it around
  `withCredentials`). Reliable for `withCredentials`-style consumers, but
  `checkout`/SCM failures surface as generic, non-attributable auth errors -
  no reliable signal to catch - and retrying re-runs the whole wrapped
  block, risking re-doing non-idempotent side effects before the failure
  point.
- **Pre-declare needed IDs, resolve before entering the block** - safe, no
  retry, and covers `checkout` too, at the cost of the pipeline author
  naming the IDs once at the wrapper (much cheaper than migrating every
  call site).

**Decision:** pre-declared IDs as the primary mechanism -
`withEphemeralCredentials([ephemeralUsernamePassword(id: 'FOO', ...)]) { ... }`.

## 6. Can the step itself live in the plugin, calling back into the script?

**Question:** Could the step be implemded in the plugin (Java or a Groovy
closure), invoking steps from the actual calling pipeline script context?
Would that be safe?

**Answer:** Yes, and this resolved the earlier "how does a Java plugin
safely drive `input`/`lock`" question properly:

- A real `Step`/`StepDescriptor` (like `lock`/`timeout`/`retry`) is the
  standard way to add something globally callable with zero `@Library`
  import - steps are just registered by function name.
- But composing *other* named steps (`lock`, `input`) from a Java step's own
  execution requires manual `StepExecution` callback-chaining - verbose and
  easy to get subtly wrong, essentially reimplementing what CPS gives
  Groovy for free.
- The actual working answer: the `GlobalVariable` extension point (the same
  mechanism that makes `env`/`params`/`currentBuild` available without an
  import) can bind a name to a Groovy object built from **source shipped as
  a plugin resource, not precompiled** - loaded on demand through the
  calling script's own `GroovyClassLoader`. This is confirmed by
  `docker-workflow-plugin`'s real, production `DockerDSL`/`Docker.groovy`,
  which does exactly this and calls `script.withDockerContainer(...)`,
  `script.sh(...)`, etc. directly. Because the resource is parsed through
  the CPS-aware classloader, it gets the same CPS transformation as a
  shared-library script - calling `lock`/`input` from it is exactly as safe
  as existing JSL code doing the same thing.

This became `WithEphemeralCredentials.groovy` (the resource) +
`WithEphemeralCredentialsGlobalVariable` (the Java registration), plus two
small factory `GlobalVariable`s (`ephemeralUsernamePassword`,
`ephemeralSecretText`) mirroring how `usernamePassword(...)`/`string(...)`
work inside `withCredentials` - each tied to an `EphemeralCredentialSpec`
subclass that knows its `input` parameters and how to build a `Credentials`
object from the answers, with `description` flowing through to `input`'s
`message`.

## 7. What actually broke when this was built and tested for real

Three real bugs surfaced only by running `WithEphemeralCredentialsTest`
against a real embedded Jenkins (`JenkinsRule`) - not by reasoning alone:

1. **`ClassNotFoundException` for the bundled `.groovy` resource.** The CPS
   script's classloader doesn't discover a plugin's `.groovy` resource via
   plain `loadClass()` the way expected from the `Docker.groovy` precedent.
   Fixed by reading the resource's source text via this plugin's own
   classloader (which can always find its own resources) and handing it
   directly to the CPS classloader's `parseClass(...)`, instead of relying
   on cross-plugin classpath discovery.

2. **`RejectedAccessException` for `currentBuild.rawBuild` and even this
   plugin's own static methods.** Since the bundled resource is compiled
   through the *calling* script's classloader, it inherits that build's
   sandbox status - every non-step Java call it makes needs an explicit
   script-security whitelist entry, the same way `credentials-binding` and
   `docker-workflow-plugin` ship their own. Fixed with
   `EphemeralCredentialsWhitelist` (`@Extension` factory method) loading
   `whitelist.txt`. Also fixed by resolving the `Run` in plain trusted Java
   (`WithEphemeralCredentialsGlobalVariable`, before any CPS/sandbox
   interpretation begins) and passing it in, rather than deriving it from
   `currentBuild.rawBuild` inside the sandboxed Groovy.

3. **`NotSerializableException` on `WorkflowRun`, and a deeper discovery
   underneath it.** Storing a live `Run` field on `WithEphemeralCredentials`
   broke CPS's own program-state serialization the moment `input()` paused
   (the object sits in the script's binding for the whole build, so CPS
   walks it on every pause; `WorkflowRun` isn't Java-serializable). Fixed by
   holding only the plain `externalizableId` String and re-resolving
   `Run.fromExternalizableId(...)` fresh right before each synchronous use,
   never across a pause - same treatment for the
   `EphemeralCredentialsProvider` singleton reference.

   While chasing this, testing surfaced something more important than the
   serialization bug itself: **`CpsThread.current()` returns `null` when
   called from inside `credentials-binding`'s own internal
   `findCredentialById` lookup** (confirmed with temporary debug logging
   against the real embedded Jenkins) - that lookup runs off the CPS
   interpreter thread entirely. This means the earlier belief that
   CpsThread-based correlation would let literal-ID lookups resolve safely
   for *any* consumer, including nested `withCredentials`, does not
   actually hold. The fix applied: when `CpsThread.current()` can't
   identify the run, `EphemeralCredentialsProvider.getCredentialsInItemGroup`
   falls back to considering every run's cache and lets the caller's own
   by-ID filtering (`CredentialsProvider.findCredentialById`) pick the
   right entry - correct as long as two different runs aren't concurrently
   caching different values under the exact same literal ID at the same
   moment. This is documented prominently in the provider's Javadoc and the
   README as a discovered limitation, not a merely theoretical caveat.

## 8. Maven conversion

Requested after the plugin was first built with Gradle (matching `jslcus`'s
toolchain), since Maven is the more common convention for Jenkins plugins
specifically. Notable friction points, resolved empirically against the
real Maven repository rather than guessed:

- The parent POM's `RequireUpperBoundDeps` enforcer rule rejected
  version conflicts between `workflow-cps`/`pipeline-input-step`/
  `lockable-resources`'s differing transitive wants (`workflow-support`,
  `script-security`, `workflow-api`, `workflow-step-api`, `scm-api`, ...).
  Pinning each conflict by hand as it surfaced turned into whack-a-mole;
  switched to importing `io.jenkins.tools.bom:bom-2.462.x` (the
  mutually-consistent version set for this exact core line) instead, which
  resolved cleanly.
- `jenkins-test-harness`'s version must come from the
  `jenkins-test-harness.version` property specifically (parent POM's
  `BannedDependencies` rule), not a literal `<version>`.
- The parent POM auto-generates a `JenkinsRule`-based smoke test
  (`InjectedTest`) when no tests exist; once real tests were added, its
  reference to a package this `jenkins-test-harness` version lacks broke
  the build - disabled via `<disabledTestInjection>true</disabledTestInjection>`.
- This version of `credentials-plugin` doesn't declare `getCredentials(...)`
  as abstract - it's a deprecated compatibility shim. The real extension
  point to override is `getCredentialsInItemGroup(...)`, confirmed by
  decompiling the resolved plugin jar.

## 9. Pre-warming: resolving credentials early, using them much later

**Question:** Would it help to run an empty `withEphemeralCredentials(ids) {}`
early in a pipeline - not wrapping any actual work, just to make sure needed
credentials get collected while the human who started the build hasn't
walked away yet, since they may actually be referenced minutes or hours
later?

**Answer:** Yes, and it works with the implementation exactly as built, no
code changes needed - `call()`'s `for` loop over declared specs runs
regardless of what the body closure contains, so an empty `{}` still
resolves/prompts/caches every declared ID, then returns immediately. Once
cached, the entry persists in the JVM-wide singleton for the life of the
run, so a much later, unmodified `withCredentials`/`checkout` call finds it
already there. Documented in the README with a worked example, plus one
caveat surfaced by reasoning through it rather than glossed over: the cache
is the in-memory singleton, which does **not** survive a controller
restart, even though a *paused* Pipeline build does. A restart between the
warm-up stage and the later stage that uses the credential leaves the build
resumed but the cache empty - safe (just an extra prompt) if the later
point also calls `withEphemeralCredentials`, but a silent failure if it's an
unmodified call relying solely on the earlier warm-up.
