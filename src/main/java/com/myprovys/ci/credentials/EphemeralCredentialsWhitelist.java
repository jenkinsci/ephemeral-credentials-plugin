package com.myprovys.ci.credentials;

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
 * credentials-binding or docker-workflow-plugin ship one for their own DSL glue.
 */
public class EphemeralCredentialsWhitelist {

    @Extension
    public static Whitelist whitelist() throws IOException {
        URL url = EphemeralCredentialsWhitelist.class.getResource("whitelist.txt");
        return StaticWhitelist.from(url);
    }
}
