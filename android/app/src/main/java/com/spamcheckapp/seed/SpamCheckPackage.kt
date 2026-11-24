package com.spamcheckapp.seed

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

/** Registers [SpamCheckModule] (legacy module, served through the New Architecture interop layer). */
class SpamCheckPackage : BaseReactPackage() {
  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
      if (name == SpamCheckModule.NAME) SpamCheckModule(reactContext) else null

  override fun getReactModuleInfoProvider(): ReactModuleInfoProvider = ReactModuleInfoProvider {
    mapOf(
        SpamCheckModule.NAME to
            ReactModuleInfo(
                name = SpamCheckModule.NAME,
                className = SpamCheckModule::class.java.name,
                canOverrideExistingModule = false,
                needsEagerInit = false,
                isCxxModule = false,
                isTurboModule = false,
            ))
  }
}
