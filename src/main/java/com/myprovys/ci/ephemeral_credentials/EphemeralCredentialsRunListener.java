/*
 * The MIT License
 *
 * Copyright (c) 2026, Jim Klimov, PROVYS Technologies a.s.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.myprovys.ci.ephemeral_credentials;

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
