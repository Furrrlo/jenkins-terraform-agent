package io.github.furrrlo.jenkins.terraform.TerraformAgentTemplate

f = namespace('/lib/form')

f.entry(field: 'name', title: _('Unique name')) {
    f.textbox()
}

f.entry(field: 'labelString', title: _('Labels')) {
    f.textbox()
}

f.entry(field: 'labellessJobsAllowed', title: _('Allow jobs with no label restriction')) {
    f.checkbox()
}

f.entry(field: 'terraformInstallation', title: _('Terraform Installation')) {
    f.select()
}

f.entry(title: _('Credentials'), description: 'List of credentials to provide to the Terraform template as variables') {
    // Defines a header so the repeats can be re-ordered
    f.repeatableProperty(field: 'credentials', header: 'Credential') {
        f.entry(title: '')  {
            f.div(align: 'right')  {
                f.repeatableDeleteButton();
            }
        }
    }
}

f.radioBlock(checked: descriptor.isInlineConfigChecked(instance), name: 'terraformConfig', value: 'inline', title: 'Configuration Text') {
    f.entry(title: 'Terraform Text Configuration', field: 'inlineConfig', description: 'Inline configuration') {
        f.textarea()
    }
}

f.radioBlock(checked: descriptor.isFileConfigChecked(instance), name: 'terraformConfig', value: 'file', title: 'Configuration Path') {
    f.entry(title: 'Terraform File Configuration', field: 'fileConfig', description: 'Relative Path to workspace directory containing configuration files') {
        f.textbox()
    }
}

f.entry(field: 'workspacePath', title: _('Jenkins workspace directory path')) {
    f.textbox(default: "/home/jenkins/agent/")
}

f.entry(field: 'useWebsocket', title: _('Connect to Jenkins using websockets')) {
    f.checkbox()
}

f.entry(title: 'Number of executors', field: _('numExecutors')) {
    f.textbox(default: '1')
}

f.entry(field: 'idleTerminationInMinutes', title: _('Idle termination time')) {
    f.textbox(default: '10')
}

f.entry(field: 'instanceCap', title: _('Instance cap')) {
    f.textbox(default: '2')
}
