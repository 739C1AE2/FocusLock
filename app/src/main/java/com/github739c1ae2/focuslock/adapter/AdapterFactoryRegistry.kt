package com.github739c1ae2.focuslock.adapter

object AdapterFactoryRegistry {
    private val adapters = mutableMapOf<String, AppAdapterFactory<*>>()

    init {
        register(GenericAdapter.Factory)
    }

    private fun register(factory: AppAdapterFactory<*>) {
        adapters[factory.adapterId] = factory
    }

    fun getFactoryById(adapterId: String): AppAdapterFactory<*> {
        return requireNotNull(adapters[adapterId])
    }

    fun getAvailableFactoriesForPackage(packageName: String): List<AppAdapterFactory<*>> {
        return adapters.values.filter { it.canHandle(packageName) }
    }
}