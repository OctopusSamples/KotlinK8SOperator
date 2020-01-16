package com.octopus.webserver.operator.crd

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.fabric8.kubernetes.api.model.KubernetesResource

@JsonDeserialize
data class WebServerStatus(var count: Int = 0) : KubernetesResource