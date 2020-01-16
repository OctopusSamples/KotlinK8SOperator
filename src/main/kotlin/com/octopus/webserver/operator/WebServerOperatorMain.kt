package com.octopus.webserver.operator

import com.octopus.webserver.operator.controller.WebServerController
import com.octopus.webserver.operator.crd.WebServer
import com.octopus.webserver.operator.crd.WebServerList
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodList
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinitionBuilder
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext


fun main(args: Array<String>) {
    val client = DefaultKubernetesClient()
    client.use {
        val namespace = client.namespace ?: "default"
        val podSetCustomResourceDefinition = CustomResourceDefinitionBuilder()
                .withNewMetadata().withName("webservers.demo.k8s.io").endMetadata()
                .withNewSpec()
                .withGroup("demo.k8s.io")
                .withVersion("v1alpha1")
                .withNewNames().withKind("WebServer").withPlural("webservers").endNames()
                .withScope("Namespaced")
                .endSpec()
                .build()
        val webServerCustomResourceDefinitionContext = CustomResourceDefinitionContext.Builder()
                .withVersion("v1alpha1")
                .withScope("Namespaced")
                .withGroup("demo.k8s.io")
                .withPlural("webservers")
                .build()
        val informerFactory = client.informers()
        val podSharedIndexInformer = informerFactory.sharedIndexInformerFor(
                Pod::class.java,
                PodList::class.java,
                10 * 60 * 1000.toLong())
        val webServerSharedIndexInformer = informerFactory.sharedIndexInformerForCustomResource(
                webServerCustomResourceDefinitionContext,
                WebServer::class.java,
                WebServerList::class.java,
                10 * 60 * 1000.toLong())
        val webServerController = WebServerController(
                client,
                podSharedIndexInformer,
                webServerSharedIndexInformer,
                podSetCustomResourceDefinition,
                namespace)

        webServerController.create()
        informerFactory.startAllRegisteredInformers()

        webServerController.run()
    }
}