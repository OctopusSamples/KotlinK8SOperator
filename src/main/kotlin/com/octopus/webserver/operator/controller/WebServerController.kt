package com.octopus.webserver.operator.controller

import com.octopus.webserver.operator.crd.DoneableWebServer
import com.octopus.webserver.operator.crd.WebServer
import com.octopus.webserver.operator.crd.WebServerList
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.ResourceEventHandler
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import io.fabric8.kubernetes.client.informers.cache.Cache
import io.fabric8.kubernetes.client.informers.cache.Lister
import java.util.*
import java.util.AbstractMap.SimpleEntry
import java.util.concurrent.ArrayBlockingQueue


class WebServerController(private val kubernetesClient: KubernetesClient,
                          private val podInformer: SharedIndexInformer<Pod>,
                          private val webServerInformer: SharedIndexInformer<WebServer>,
                          private val webServerResourceDefinition: CustomResourceDefinition,
                          private val namespace: String) {
    private val APP_LABEL = "app"
    private val webServerLister = Lister<WebServer>(webServerInformer.indexer, namespace)
    private val podLister = Lister<Pod>(podInformer.indexer, namespace)
    private val workQueue = ArrayBlockingQueue<String>(1024)

    fun create() {
        webServerInformer.addEventHandler(object : ResourceEventHandler<WebServer> {
            override fun onAdd(webServer: WebServer) {
                enqueueWebServer(webServer)
            }

            override fun onUpdate(webServer: WebServer, newWebServer: WebServer) {
                enqueueWebServer(newWebServer)
            }

            override fun onDelete(webServer: WebServer, b: Boolean) {}
        })

        podInformer.addEventHandler(object : ResourceEventHandler<Pod> {
            override fun onAdd(pod: Pod) {
                handlePodObject(pod)
            }

            override fun onUpdate(oldPod: Pod, newPod: Pod) {
                if (oldPod.metadata.resourceVersion == newPod.metadata.resourceVersion) {
                    return
                }
                handlePodObject(newPod)
            }

            override fun onDelete(pod: Pod, b: Boolean) {}
        })
    }

    private fun enqueueWebServer(webServer: WebServer) {
        val key: String = Cache.metaNamespaceKeyFunc(webServer)
        if (key.isNotEmpty()) {
            workQueue.add(key)
        }
    }

    private fun handlePodObject(pod: Pod) {
        val ownerReference = getControllerOf(pod)

        if (ownerReference?.kind?.equals("WebServer", ignoreCase = true) != true) {
            return
        }

        webServerLister
                .get(ownerReference.name)
                ?.also { enqueueWebServer(it) }
    }

    private fun getControllerOf(pod: Pod): OwnerReference? =
            pod.metadata.ownerReferences.firstOrNull { it.controller }

    private fun reconcile(webServer: WebServer) {
        val pods = podCountByLabel(APP_LABEL, webServer.metadata.name)
        val existingPods = pods.size

        webServer.status.count = existingPods
        updateStatus(webServer)

        if (existingPods < webServer.spec.replicas) {
            createPod(webServer)
        } else if (existingPods > webServer.spec.replicas) {
            kubernetesClient
                    .pods()
                    .inNamespace(webServer.metadata.namespace)
                    .withName(pods[0])
                    .delete()
        }
    }

    private fun updateStatus(webServer: WebServer) =
            kubernetesClient.customResources(webServerResourceDefinition, WebServer::class.java, WebServerList::class.java, DoneableWebServer::class.java)
                    .inNamespace(webServer.metadata.namespace)
                    .withName(webServer.metadata.name)
                    .updateStatus(webServer)

    private fun podCountByLabel(label: String, webServerName: String): List<String> =
            podLister.list()
                    .filter { it.metadata.labels.entries.contains(SimpleEntry(label, webServerName)) }
                    .filter { it.status.phase == "Running" || it.status.phase == "Pending" }
                    .map { it.metadata.name }

    private fun createPod(webServer: WebServer) =
            createNewPod(webServer).let { pod ->
                kubernetesClient.pods().inNamespace(webServer.metadata.namespace).create(pod)
            }

    private fun createNewPod(webServer: WebServer): Pod =
            PodBuilder()
                    .withNewMetadata()
                    .withGenerateName(webServer.metadata.name.toString() + "-pod")
                    .withNamespace(webServer.metadata.namespace)
                    .withLabels(Collections.singletonMap(APP_LABEL, webServer.metadata.name))
                    .addNewOwnerReference()
                    .withController(true)
                    .withKind("WebServer")
                    .withApiVersion("demo.k8s.io/v1alpha1")
                    .withName(webServer.metadata.name)
                    .withNewUid(webServer.metadata.uid)
                    .endOwnerReference()
                    .endMetadata()
                    .withNewSpec()
                    .addNewContainer().withName("nginx").withImage("nginxdemos/hello").endContainer()
                    .endSpec()
                    .build()

    fun run() {
        blockUntilSynced()
        while (true) {
            try {
                workQueue
                        .take()
                        .split("/")
                        .toTypedArray()[1]
                        .let { webServerLister.get(it) }
                        ?.also { reconcile(it) }
            } catch (interruptedException: InterruptedException) {
                // ignored
            }
        }
    }

    private fun blockUntilSynced() {
        while (!podInformer.hasSynced() || !webServerInformer.hasSynced()) {}
    }
}