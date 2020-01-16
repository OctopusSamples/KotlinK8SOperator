package com.octopus.webserver.operator.crd

import io.fabric8.kubernetes.client.CustomResource

data class WebServer(var spec: WebServerSpec = WebServerSpec(),
                     var status: WebServerStatus = WebServerStatus()) : CustomResource()