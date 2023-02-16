package io.github.furrrlo.jenkins.terraform.TerraformCloud

f = namespace('/lib/form')

f.entry(field: 'name', title: _('Unique name')) {
    f.textbox()
}

f.entry(field: 'timeoutMinutes', title: _('Timeout in minutes')) {
    f.textbox(default: '10')
}

f.entry(field: 'agentTimeoutMinutes', title: _('Agent timeout in minutes')) {
    f.textbox(default: '10')
}

f.entry(title: _('Templates'), description: 'List of Terraform templates which can be used to launch agents') {
    // Defines a header so the repeats can be re-ordered
    f.repeatableProperty(field: 'templates', header: 'Template') {
        f.entry(title: '')  {
            f.div(align: 'right')  {
                f.repeatableDeleteButton();
            }
        }
    }
}
