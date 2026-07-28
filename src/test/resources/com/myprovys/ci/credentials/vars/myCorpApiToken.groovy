// Example JSL-style global variable: an independently-named factory for
// withEphemeralCredentials, defined entirely in a shared library rather
// than in the EphemeralCredentialsProvider plugin itself. Reuses an
// existing wrapped credential type (secret text, via
// com.example.jsl.MyCorpApiTokenSpec below) to prove the extension
// mechanism without needing a genuinely new Credentials implementation -
// only this factory function and its spec class are independently owned.
def call(Map args) {
    return new com.example.jsl.MyCorpApiTokenSpec(args.id, args.description)
}
