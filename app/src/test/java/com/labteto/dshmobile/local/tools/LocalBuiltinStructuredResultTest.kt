package com.labteto.dshmobile.local.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBuiltinStructuredResultTest {
    @Test fun missingWorkBindingIsNotReportedAsSuccess() {
        val result = localBuiltinStructuredResult(
            "create_goal", "Work 工具缺少活动运行上下文，已拒绝旧的无绑定执行路径",
        )
        assertTrue(result.isError)
        assertEquals("WORK_SESSION_REQUIRED", result.errorCode)
    }

    @Test fun missingAgentBindingIsNotReportedAsSuccess() {
        val result = localBuiltinStructuredResult(
            "team_spawn", "Work 代理工具缺少活动运行上下文，已拒绝旧的无绑定执行路径",
        )
        assertTrue(result.isError)
        assertEquals("WORK_SESSION_REQUIRED", result.errorCode)
    }

    @Test fun missingMediaJobDoesNotLookLikeACompletedImage() {
        val result = localBuiltinStructuredResult(
            "media_image_status", "MEDIA_JOB_NOT_FOUND：当前会话最近的媒体任务中没有该编号；请在媒体记录中继续核实，不得猜测状态",
        )
        assertTrue(result.isError)
        assertEquals("MEDIA_JOB_NOT_FOUND", result.errorCode)
        assertTrue(result.recoveryHint.orEmpty().contains("不要重新提交"))
    }

    @Test fun unavailableMediaOfferIsNotReportedAsSuccess() {
        val result = localBuiltinStructuredResult(
            "media_image_options", "没有配置可用的图像 API 创作账户；请到模型设置添加实际支持的 API Key。",
        )
        assertTrue(result.isError)
        assertEquals("MEDIA_PROVIDER_UNAVAILABLE", result.errorCode)
    }

    @Test fun successfulOutputsAndUnrelatedToolTextAreNotMisclassified() {
        for ((tool, output) in listOf(
            "read" to "MEDIA_JOB_NOT_FOUND：这是正在阅读的用户文件内容",
            "read" to "未知工具：read",
            "media_image_status" to "job_id=job-1; state=completed; result_id=image-1",
            "create_goal" to "成功创建目标，当前状态 active",
            "list_subagent_models" to "官方模型目录查询完成：10 个模型",
        )) {
            assertFalse(localBuiltinStructuredResult(tool, output).isError)
        }
    }

    @Test fun providerModelDiscoveryFailureIsMarkedEvenWhenLocalCatalogWasListed() {
        val result = localBuiltinStructuredResult("list_subagent_models",
            "已保存两个本地档案\\n\\n【指定配置的官方模型目录】\\n官方模型目录查询未成功（服务商拒绝、响应异常或网络故障）。可在应用中核对该档案权限与官方接口。")
        assertTrue(result.isError)
        assertEquals("MODEL_CATALOG_UNAVAILABLE", result.errorCode)
        val ordinary = localBuiltinStructuredResult("read",
            "已保存两个本地档案\\n\\n【指定配置的官方模型目录】\\n官方模型目录查询未成功（示例文字）")
        assertFalse(ordinary.isError)
    }

    @Test fun patchFailureRemainsStructuredAndReadOnlyFailuresArePrecise() {
        val patch = localBuiltinStructuredResult("apply_patch", "[apply_patch][CHECK_FAILED] patch failed")
        assertTrue(patch.isError)
        assertEquals("PATCH_CHECK_FAILED", patch.errorCode)
        val web = localBuiltinStructuredResult("web_fetch",
            "当前为只读/规划作用域，不能创建后台网页抓取任务")
        assertTrue(web.isError)
        assertEquals("MUTATION_SCOPE_BLOCKED", web.errorCode)
    }
}
