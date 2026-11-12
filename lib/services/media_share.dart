import 'dart:io';

import 'package:flutter/services.dart';

/// Opens the Android share sheet for a file via the native side.
///
/// The native side copies the file into MediaStore and shares that copy, so the receiving app
/// (Discord) can keep reading it while it uploads in the background. Sharing straight from the
/// app's own storage left uploads stuck on "sending" once Android froze the backgrounded app.
class MediaShare {
  static const _channel = MethodChannel('instaembed/share');

  /// Shares [file]; returns once the share sheet is open. The original file may be removed.
  static Future<void> shareFile(File file) async {
    final lower = file.path.toLowerCase();
    final mimeType = lower.endsWith('.jpg') || lower.endsWith('.jpeg') ? 'image/jpeg' : 'video/mp4';
    await _channel.invokeMethod('shareFile', {'path': file.path, 'mimeType': mimeType});
  }
}
