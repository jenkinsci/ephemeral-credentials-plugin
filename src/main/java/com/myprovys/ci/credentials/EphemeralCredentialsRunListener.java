package com.myprovys.ci.credentials;

import hudson.Extension;
import hudson.model.Run;
import hudson.model.listeners.RunListener;

/**
 * Authoritative cleanup backstop for {@link EphemeralCredentialsProvider}:
 *
 * <ul>
 *     <li>{@code onFinalized} fires for every way a build can end -- success,
 * failure, or a hard kill/abort -- independently of whether the Pipeline
 * script itself got to run its own {@code finally} block.</li>
 *     <li>{@code onDeleted} covers a build record being removed later.</li>
 * </ul>
 *
 * Either way, nothing this plugin has cached for that build should outlive it.
 */
@Extension
public class EphemeralCredentialsRunListener extends RunListener<Run<?, ?>> {

    @Override
    public void onFinalized(Run<?, ?> run) {
        EphemeralCredentialsProvider.get().forget(run);
    }

    @Override
    public void onDeleted(Run<?, ?> run) {
        EphemeralCredentialsProvider.get().forget(run);
    }
}
