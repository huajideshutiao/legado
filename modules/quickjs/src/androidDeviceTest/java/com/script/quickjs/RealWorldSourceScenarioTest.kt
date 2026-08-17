package com.script.quickjs

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/**
 * Android 设备端真实书源场景测试 (薄子类)。
 *
 * 测试逻辑全部在 commonTest 的 [RealWorldSourceScenarioTestBase],
 * 本端仅多 AndroidJUnit4 runner, 无其他平台差异。
 */
@RunWith(AndroidJUnit4::class)
class RealWorldSourceScenarioTest : RealWorldSourceScenarioTestBase()
