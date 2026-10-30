package com.openminis.app.ui.chat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.tools.FileReadTool
import com.openminis.app.tools.FileWriteTool
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URLEncoder
@RunWith(AndroidJUnit4::class)
class ChatFileDeliveryTest {
    @Test fun writtenUnicodeFileOpensInItsSessionAndMissingFileIsAnError() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val sid = "quality-file-test"; val path = "/var/minis/workspace/交付 + 测试.md"
        val content = "# 核对结果\n实际内容 123.45 元\n"
        val args = JSONObject().put("path", path).put("content", content).put("tool_title", "write")
        val f = PRootKernel.resolveSessionHostPath(sid, path, ctx)!!
        try {
            val write = FileWriteTool.execute(args.toString(), sid, ctx)
            assertTrue(write.output, write.success); assertEquals(content, f.readText())
            val url = "minis://workspace/" + URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")
            val open = ChatLinkResolver.resolve(url, sid, ctx)
            assertTrue(open is ChatLinkAction.SandboxFile)
            assertEquals(f.canonicalPath, (open as ChatLinkAction.SandboxFile).item.file.canonicalPath)
            assertTrue(ChatLinkResolver.resolve(url, "quality-other-test", ctx) is ChatLinkAction.MissingFile)
            assertTrue(ChatLinkResolver.resolve("file:///missing%ZZ.csv", sid, ctx) is ChatLinkAction.MissingFile)
            f.delete(); assertTrue(ChatLinkResolver.resolve(url, sid, ctx) is ChatLinkAction.MissingFile)
            assertFalse(FileReadTool.execute(JSONObject().put("path",path).toString(),sid,ctx).success)
        } finally { f.parentFile?.deleteRecursively() }
    }
    @Test fun directoryWriteFailsAndCannotMasqueradeAsAFile() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val sid = "quality-dir-test"; val path = "/var/minis/workspace/existing-dir"
        val dir = PRootKernel.resolveSessionHostPath(sid, path, ctx)!!; dir.mkdirs()
        try {
            val result = FileWriteTool.execute(JSONObject().put("path",path).put("content","deliverable").toString(),sid,ctx)
            assertFalse(result.success)
            assertTrue(ChatLinkResolver.resolve("minis://workspace/existing-dir", sid, ctx) is ChatLinkAction.SandboxFolder)
        } finally { dir.parentFile?.deleteRecursively() }
    }
}
