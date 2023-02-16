package io.github.furrrlo.jenkins.terraform;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TerraformAgentName {

    private static final String PREFIX = "jenkins-terraform";
    private static final String CLOUD_REGEX = "([a-zA-Z\\d.]+)";
    private static final String TEMPLATE_REGEX = "([a-zA-Z\\d.]+)";
    private static final String UUID_REGEX = "\\p{XDigit}{8}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{12}";

    private static final String DROPLET_REGEX = Pattern.quote(PREFIX) + "-" + CLOUD_REGEX + "-" + TEMPLATE_REGEX + "-" + UUID_REGEX;

    private static final Pattern CLOUD_PATTERN = Pattern.compile("^" + CLOUD_REGEX + "$");
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("^" + TEMPLATE_REGEX + "$");
    private static final Pattern DROPLET_PATTERN = Pattern.compile("^" + DROPLET_REGEX + "$");

    private TerraformAgentName() {
    }

    public static boolean isValidCloudName(String cloudName) {
        return CLOUD_PATTERN.matcher(cloudName).matches();
    }

    public static boolean isValidTemplateName(String slaveName) {
        return TEMPLATE_PATTERN.matcher(slaveName).matches();
    }

    public static String generateAgentName(String cloudName, String templateName) {
        return PREFIX + "-" + cloudName + "-" + templateName + "-" + UUID.randomUUID();
    }

    public static boolean isNodeInstanceOfCloud(String dropletName, String cloudName) {
        Matcher m = DROPLET_PATTERN.matcher(dropletName);
        return m.matches() && m.group(1).equals(cloudName);
    }

    public static boolean isNodeInstanceOfTemplate(String dropletName, String cloudName, String slaveName) {
        Matcher m = DROPLET_PATTERN.matcher(dropletName);
        return m.matches() && m.group(1).equals(cloudName) && m.group(2).equals(slaveName);
    }
}
