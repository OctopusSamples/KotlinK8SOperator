package com.octopus.webserver.operator

import com.octopus.webserver.operator.controller.WebServerController
import com.octopus.webserver.operator.crd.DoneableWebServer
import com.octopus.webserver.operator.crd.WebServer
import com.octopus.webserver.operator.crd.WebServerList
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodList
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinitionBuilder
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.Resource
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext
import io.fabric8.kubernetes.client.informers.SharedIndexInformer


fun main(args: Array<String>) {
    val client = DefaultKubernetesClient()
    client.use {
        val namespace = client.namespace ?: "default"
        val webServerCustomResourceDefinition = CustomResourceDefinitionBuilder()
                .withNewMetadata().withName("podsets.demo.k8s.io").endMetadata()
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
                .withPlural("podsets")
                .build()
        val informerFactory = client.informers()
        val webServerClient: MixedOperation<WebServer, WebServerList, DoneableWebServer, Resource<WebServer, DoneableWebServer>> =
                client.customResources(
                        webServerCustomResourceDefinition,
                        WebServer::class.java,
                        WebServerList::class.java,
                        DoneableWebServer::class.java)
        val podSharedIndexInformer = informerFactory.sharedIndexInformerFor(
                Pod::class.java,
                PodList::class.java,
                10 * 60 * 1000.toLong())
        val webServerSharedIndexInformer = informerFactory.sharedIndexInformerForCustomResource(
                webServerCustomResourceDefinitionContext,
                WebServer::class.java,
                WebServerList::class.java,
                10 * 60 * 1000.toLong())
        val webServerController = WebServerController(client, webServerClient, podSharedIndexInformer, webServerSharedIndexInformer, namespace)

        webServerController.create()
        informerFactory.startAllRegisteredInformers()

        webServerController.run()
    }
}