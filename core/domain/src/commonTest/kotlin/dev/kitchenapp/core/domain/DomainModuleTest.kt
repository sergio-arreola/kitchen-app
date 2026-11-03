package dev.kitchenapp.core.domain

import kotlin.test.Test
import kotlin.test.assertNotNull

class DomainModuleTest {
    @Test
    fun moduleLoads() {
        assertNotNull(DomainModule)
    }
}
