package io.github.neuralcoherence.probe.core

import java.util.concurrent.atomic.AtomicReference

internal enum class ModuleTask {
    IDLE,
    SCANNING,
    INTERACTING,
}

/** Ensures that a dry scan and an interaction batch can never overlap or queue together. */
internal class ModuleTaskCoordinator {
    private val current = AtomicReference(ModuleTask.IDLE)

    val state: ModuleTask
        get() = current.get()

    fun tryStart(task: ModuleTask): Boolean =
        task != ModuleTask.IDLE && current.compareAndSet(ModuleTask.IDLE, task)

    fun finish(task: ModuleTask): Boolean = current.compareAndSet(task, ModuleTask.IDLE)
}
