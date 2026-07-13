package io.ethan.pushgo.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePerformanceArchitectureContractTest {
    @Test
    fun listAndSearchPagingCannotReturnFullMessageEntities() {
        val dao = readSource("data/db/MessageDao.kt")
        val listViewModel = readSource("ui/viewmodel/MessageListViewModel.kt")
        val searchViewModel = readSource("ui/viewmodel/MessageSearchViewModel.kt")

        assertTrue(dao.contains("PagingSource<Int, MessageListRow>"))
        assertFalse(dao.contains("PagingSource<Int, MessageEntity>"))
        assertFalse(dao.contains("Flow<List<MessageEntity>>"))
        assertTrue(listViewModel.contains("Flow<PagingData<MessageListItem>>"))
        assertTrue(searchViewModel.contains("Flow<PagingData<MessageListItem>>"))
        assertFalse(searchViewModel.contains("Flow<List<PushMessage>>"))
    }

    @Test
    fun listAndSearchUiCannotCallFullMessageExportOrInjectMessageDao() {
        val uiSources = listOf(
            readSource("ui/viewmodel/MessageListViewModel.kt"),
            readSource("ui/viewmodel/MessageSearchViewModel.kt"),
            readSource("ui/screens/MessageListScreen.kt"),
            readSource("ui/screens/MessageSearchScreen.kt"),
        ).joinToString("\n")
        assertFalse(uiSources.contains("loadAllForExport("))
        assertFalse(uiSources.contains("MessageDao"))
        assertFalse(uiSources.contains("messageDao()"))
    }

    @Test
    fun countAndFacetViewModelPathsUseRepositoryContracts() {
        val listViewModel = readSource("ui/viewmodel/MessageListViewModel.kt")
        assertTrue(listViewModel.contains("repository.observeUnreadCount"))
        assertTrue(listViewModel.contains("repository.observeFacetChannelCounts"))
        assertTrue(listViewModel.contains("repository.observeFacetTagCounts"))
        assertFalse(listViewModel.contains("loadAllForExport"))
    }

    private fun readSource(relativePath: String): String {
        val file = File("src/main/java/io/ethan/pushgo/$relativePath")
        require(file.exists()) { "Missing expected source file: $relativePath" }
        return file.readText()
    }
}
