package app.kassa.money.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InboxCleanupTest {

    private val inbox = "/var/mobile/Containers/Data/Application/X/Documents/Inbox/backup.kassa"
    private val shared = "/var/mobile/Containers/Data/Application/X/tmp/app.kassa.money-Inbox/data.sql"

    @Test
    fun onlyFilesInAnInboxFolderCountAsInboxFiles() {
        assertTrue(InboxCleanup.isInboxFile(inbox))
        assertTrue(InboxCleanup.isInboxFile(shared))
        assertFalse(InboxCleanup.isInboxFile("/var/mobile/Documents/backup.kassa"))
        assertFalse(InboxCleanup.isInboxFile("/var/mobile/Documents/Inbox"))
        assertFalse(InboxCleanup.isInboxFile("/var/mobile/Documents/Inbox/"))
        assertFalse(InboxCleanup.isInboxFile("/var/mobile/Documents/MyInbox/backup.kassa"))
        assertFalse(InboxCleanup.isInboxFile("/var/mobile/Documents/-Inbox/backup.kassa"))
        assertFalse(InboxCleanup.isInboxFile("/var/mobile/Documents/Inbox/deeper/backup.kassa"))
        assertFalse(InboxCleanup.isInboxFile(""))
        assertFalse(InboxCleanup.isInboxFile("backup.kassa"))
    }

    @Test
    fun aCopyGoesOnceNoImportReadsItAnyMore() {
        assertFalse(InboxCleanup.removeAfterFlow(inbox, flowActive = true))
        assertTrue(InboxCleanup.removeAfterFlow(inbox, flowActive = false))
        assertTrue(InboxCleanup.removeAfterFlow(shared, flowActive = false))
        assertFalse(InboxCleanup.removeAfterFlow("/var/mobile/Documents/picked.kassa", flowActive = false))
    }

    @Test
    fun launchKeepsTheFileTheAppWasOpenedWith() {
        val other = "/var/mobile/Containers/Data/Application/X/Documents/Inbox/old.mmbak"
        val outside = "/var/mobile/Containers/Data/Application/X/Documents/notes.txt"
        val found = listOf(inbox, other, shared, outside)
        assertEquals(listOf(other, shared), InboxCleanup.staleAtLaunch(found, listOf(inbox)))
        assertEquals(listOf(inbox, other, shared), InboxCleanup.staleAtLaunch(found, emptyList()))
        assertEquals(listOf(inbox, shared), InboxCleanup.staleAtLaunch(found, listOf(other, "")))
        assertEquals(emptyList<String>(), InboxCleanup.staleAtLaunch(emptyList(), listOf(inbox)))
    }

    @Test
    fun theSameFileUnderPrivateIsStillTheSameFile() {
        val under = "/private" + inbox
        assertEquals(emptyList<String>(), InboxCleanup.staleAtLaunch(listOf(inbox), listOf(under)))
        assertEquals(emptyList<String>(), InboxCleanup.staleAtLaunch(listOf(under), listOf(inbox)))
    }
}
