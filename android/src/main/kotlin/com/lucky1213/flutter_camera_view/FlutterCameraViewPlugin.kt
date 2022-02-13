package com.lucky1213.flutter_camera_view

import io.flutter.plugin.common.PluginRegistry.Registrar
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodChannel


class FlutterCameraViewPlugin: FlutterPlugin {
  private lateinit var channel: MethodChannel

  override fun onAttachedToEngine(binding FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(binding.binaryMessenger, "flutter_camera_view_channel")
    binding.platformViewRegistry().registerViewFactory("flutter_camera_view", AndroidCameraViewFactory(channel))
  }

  override fun onDetachedFromEngine(binding FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }
}
