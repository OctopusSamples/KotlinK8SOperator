package com.octopus.webserver.operator.controller

import com.octopus.webserver.operator.crd.WebServer
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
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
                          private val namespace: String) {
    private val APP_LABEL = "app"
    private val webServerLister = Lister<WebServer>(webServerInformer.indexer, namespace)
    private val podLister = Lister<Pod>(podInformer.indexer, namespace)
    private val workqueue = ArrayBlockingQueue<String>(1024)

    fun create() {
        webServerInformer.addEventHandler(object: ResourceEventHandler<WebServer> {
            override fun onAdd(webServer: WebServer) {
                enqueueWebServer(webServer)
            }

            override fun onUpdate(webServer: WebServer, newWebServer: WebServer) {
                enqueueWebServer(newWebServer)
            }

            override fun onDelete(webServer: WebServer, b: Boolean) { }
        })

        podInformer.addEventHandler(object: ResourceEventHandler<Pod> {
            override fun onAdd(pod:Pod) {
                handlePodObject(pod)
            }

            override fun onUpdate(oldPod: Pod , newPod: Pod) {
                if (oldPod.metadata.resourceVersion == newPod.metadata.resourceVersion) {
                    return
                }
                handlePodObject(newPod)
            }

            override fun onDelete(pod:Pod , b: Boolean) { }
        })
    }

    private fun enqueueWebServer(webServer: WebServer) {
        val key: String = Cache.metaNamespaceKeyFunc(webServer)
        if (key.isNotEmpty()) {
            workqueue.add(key)
        }
    }

    private fun handlePodObject(pod: Pod) {
        val ownerReference = getControllerOf(pod)
        if (ownerReference == null || !ownerReference.kind.equals("WebServer", ignoreCase = true)) {
            return
        }
        val webServer: WebServer = webServerLister.get(ownerReference.name)
        if (webServer != null) {
            enqueueWebServer(webServer)
        }
    }

    private fun getControllerOf(pod: Pod): OwnerReference? {
        val ownerReferences = pod.metadata.ownerReferences
        for (ownerReference in ownerReferences) {
            if (ownerReference.controller) {
                return ownerReference
            }
        }
        return null
    }

    private fun reconcile(webServer: WebServer) {
        val pods = podCountByLabel(APP_LABEL, webServer.metadata.name)
        val existingPods = pods.size

        if (existingPods < webServer.spec.replicas) {
            createPods(webServer.spec.replicas - existingPods, webServer)
        } else {
            val diff: Int = existingPods - webServer.spec.replicas
            for (index in 1..diff) {
                val podName: String = pods[index]
                kubernetesClient.pods().inNamespace(webServer.metadata.namespace).withName(podName).delete()
            }
        }
    }

    private fun podCountByLabel(label: String, webServerName: String): List<String> {
        val podNames: MutableList<String> = ArrayList()
        val pods = podLister.list()
        for (pod in pods) {
            if (pod.metadata.labels.entries.contains(SimpleEntry(label, webServerName))) {
                if (pod.status.phase == "Running" || pod.status.phase == "Pending") {
                    podNames.add(pod.metadata.name)
                }
            }
        }
        return podNames
    }

    private fun createPods(numberOfPods: Int, webServer: WebServer) {
        for (index in 0 until numberOfPods) {
            val pod = createNewPod(webServer)
            kubernetesClient.pods().inNamespace(webServer.metadata.namespace).create(pod)
        }
    }

    private fun createNewPod(webServer: WebServer): Pod {
        return PodBuilder()
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
    }

    fun run() {
        while (!podInformer.hasSynced() || !webServerInformer.hasSynced());
        while (true) {
            try {
                val key = workqueue.take()
                val name = key.split("/").toTypedArray()[1]
                val webServer = webServerLister.get(name) ?: return
                reconcile(webServer)
            } catch (interruptedException: InterruptedException) {
                // ignored
            }
        }
    }
}