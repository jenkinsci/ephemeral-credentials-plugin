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
import java.io.IOException;
import java.net.URL;
import org.jenkinsci.plugins.scriptsecurity.sandbox.Whitelist;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.StaticWhitelist;

/**
 * The {@code WithEphemeralCredentials.groovy} file is compiled through the
 * calling build's own (possibly sandboxed) CpsScript classloader -- see
 * {@link WithEphemeralCredentialsGlobalVariable} -- so it inherits whatever
 * sandbox restrictions that build's Jenkinsfile is under. Every non-step Java
 * call it makes (including back into this plugin's own classes) therefore
 * needs an explicit script-security whitelist entry, the same way
 * ephemeral_credentials-binding or docker-workflow-plugin ship one for their own DSL glue.
 */
public class EphemeralCredentialsWhitelist {

    @Extension
    public static Whitelist whitelist() throws IOException {
        URL url = EphemeralCredentialsWhitelist.class.getResource("whitelist.txt");
        return StaticWhitelist.from(url);
    }
}
