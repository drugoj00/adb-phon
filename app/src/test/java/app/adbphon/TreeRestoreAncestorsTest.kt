package app.adbphon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 20.11.2026: после перезапуска у раскрытой папки раскрыта вся цепочка предков до корня тома (под «Карта памяти» не одна «Download», а полный список). */
class TreeRestoreAncestorsTest {
    @Test fun sdCardFolderBringsItsVolumeRow() {
        // сохранилась только вложенная папка: том раскрывается, чтобы были видны все его папки, а не одна
        assertEquals(listOf("L|/storage/107D-CBFB", "L|/storage/107D-CBFB/Download"), TreeOpen.withAncestors(listOf("L|/storage/107D-CBFB/Download")))
    }

    @Test fun deepNestingKeepsOrderAndNoDuplicates() {
        val r = TreeOpen.withAncestors(listOf("L|/storage/AAAA-1111/a/b/c", "L|/storage/AAAA-1111/a", "lan", "N|smb|10.0.0.2|u|/x"))
        assertEquals(listOf("L|/storage/AAAA-1111", "L|/storage/AAAA-1111/a", "L|/storage/AAAA-1111/a/b", "L|/storage/AAAA-1111/a/b/c", "lan", "N|smb|10.0.0.2|u|/x"), r)
    }

    @Test fun internalMemoryAndAlreadyCompleteChain() {
        assertEquals(listOf("L|/sdcard", "L|/sdcard/DCIM", "L|/sdcard/DCIM/Camera"), TreeOpen.withAncestors(listOf("L|/sdcard/DCIM/Camera")))
        assertEquals(listOf("L|/sdcard", "L|/sdcard/Download"), TreeOpen.withAncestors(listOf("L|/sdcard", "L|/sdcard/Download")))
    }

    @Test fun volumeRootsAndNonVolumePaths() {
        assertEquals("/storage/107D-CBFB", TreeOpen.volumeRoot("/storage/107D-CBFB/Download/x"))
        assertEquals("/storage/emulated/0", TreeOpen.volumeRoot("/storage/emulated/0/Music"))
        assertEquals("/sdcard", TreeOpen.volumeRoot("/sdcard/Music"))
        assertNull(TreeOpen.volumeRoot("/data/local/tmp"))
        assertNull(TreeOpen.volumeRoot("/storage/emulated"))
        // вне томов (Root «/», /data): предки не добавляются, узел остаётся как есть
        assertEquals(listOf("L|/data/local/tmp"), TreeOpen.withAncestors(listOf("L|/data/local/tmp")))
        assertEquals(listOf("L|/"), TreeOpen.withAncestors(listOf("L|/")))
    }
}
