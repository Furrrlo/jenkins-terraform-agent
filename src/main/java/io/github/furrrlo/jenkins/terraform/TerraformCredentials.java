package io.github.furrrlo.jenkins.terraform;

import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.IdCredentials;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.matchers.InstanceOfMatcher;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.ItemGroup;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class TerraformCredentials extends AbstractDescribableImpl<TerraformCredentials> {

    private static final List<Class<? extends IdCredentials>> SUPPORTED_CREDENTIAL_TYPES = Arrays.asList(
            StandardUsernamePasswordCredentials.class,
            StringCredentials.class);

    private final String variable;
    private final String credentialsId;

    @DataBoundConstructor
    public TerraformCredentials(String variable, String credentialsId) {
        this.variable = variable;
        this.credentialsId = credentialsId;
    }

    public String getVariable() {
        return variable;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public static List<IdCredentials> getCredentials(ItemGroup<?> context, Collection<String> ids) {
        return CredentialsMatchers.filter(
                CredentialsProvider.lookupCredentials(
                        IdCredentials.class,
                        context,
                        ACL.SYSTEM,
                        Collections.emptyList()),
                CredentialsMatchers.allOf(
                        CredentialsMatchers.anyOf(SUPPORTED_CREDENTIAL_TYPES.stream()
                                .map(InstanceOfMatcher::new)
                                .toArray(CredentialsMatcher[]::new)),
                        CredentialsMatchers.anyOf(ids.stream()
                                .map(CredentialsMatchers::withId)
                                .toArray(CredentialsMatcher[]::new))));
    }

    @Extension
    @SuppressWarnings("unused")
    public static final class DescriptorImpl extends Descriptor<TerraformCredentials> {

        @Override
        public @Nonnull String getDisplayName() {
            return "Terraform Agent Template Credentials";
        }

        public FormValidation doCheckVariable(@QueryParameter String variable) {
            return variable == null || variable.isEmpty() ?
                    FormValidation.error("Must be set") :
                    FormValidation.ok();
        }

        public ListBoxModel doFillCredentialsIdItems(
                @AncestorInPath ItemGroup<?> context,
                @QueryParameter String credentialsId) {
            final StandardListBoxModel result = new StandardListBoxModel();
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER))
                return result.includeCurrentValue(credentialsId);

            return result
                    .includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM,
                            context,
                            StandardCredentials.class,
                            Collections.emptyList(),
                            CredentialsMatchers.anyOf(SUPPORTED_CREDENTIAL_TYPES.stream()
                                    .map(InstanceOfMatcher::new)
                                    .toArray(CredentialsMatcher[]::new)))
                    .includeCurrentValue(credentialsId);
        }

        public FormValidation doCheckCredentialsId(
                @AncestorInPath ItemGroup<?> context,
                @QueryParameter String value) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER))
                return FormValidation.ok();

            if (value == null || value.isEmpty())
                return FormValidation.ok();

            if (value.startsWith("${") && value.endsWith("}"))
                return FormValidation.warning("Cannot validate expression based credentials");

            if (getCredentials(context, Collections.singletonList(value)).isEmpty())
                return FormValidation.error("Cannot find currently selected credentials");

            return FormValidation.ok();
        }
    }

    @Override
    public String toString() {
        return "Credentials{" +
                "variable='" + variable + '\'' +
                ", credentialsId='" + credentialsId + '\'' +
                '}';
    }
}
