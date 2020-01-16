package com.octopus.webserver.operator.crd

import io.fabric8.kubernetes.client.CustomResourceDoneable
import io.fabric8.kubernetes.api.builder.Function

class DoneableWebServer(resource: WebServer, function: Function<WebServer,WebServer>) : CustomResourceDoneable<WebServer>(resource, function)