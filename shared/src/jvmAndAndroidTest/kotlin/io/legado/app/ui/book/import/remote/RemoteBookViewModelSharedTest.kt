package io.legado.app.ui.book.import.remote

import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.model.remote.RemoteBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteBookViewModelSharedTest {

    private val viewModel = RemoteBookViewModelShared(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        operations = object : RemoteBookOperations {
            override suspend fun listFiles(
                path: String,
                authorization: Authorization,
            ): List<WebDavFile> = emptyList()

            override suspend fun importRemoteBook(
                authorization: Authorization,
                serverID: Long?,
                remoteBook: RemoteBook,
            ) = Unit
        },
    )

    @Test
    fun `排序状态切换保持原版升降序规则`() {
        viewModel.sortCheck(RemoteBookSort.Default, { _, _ -> }, reorderCurrent = false)
        assertEquals(RemoteBookSort.Default, viewModel.sortKey)
        assertEquals(true, viewModel.sortAscending)

        viewModel.sortCheck(RemoteBookSort.Name, { _, _ -> }, reorderCurrent = false)
        assertEquals(RemoteBookSort.Name, viewModel.sortKey)
        assertEquals(true, viewModel.sortAscending)

        viewModel.sortCheck(RemoteBookSort.Name, { _, _ -> }, reorderCurrent = false)
        assertEquals(false, viewModel.sortAscending)
    }
}
