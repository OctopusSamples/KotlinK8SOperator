package com.octopus.webserver.operator.crd

import com.fasterxml.jackson.databind.annotation.JsonDeserialize

@JsonDeserialize
data class WebServerStatus(var count: Int = 0)