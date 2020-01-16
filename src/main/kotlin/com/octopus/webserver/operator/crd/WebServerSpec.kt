package com.octopus.webserver.operator.crd

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.fabric8.kubernetes.api.model.KubernetesResource

@JsonDeserialize
data class WebServerSpec(val content: String = "", val replicas: Int = 0) : KubernetesResource {

}