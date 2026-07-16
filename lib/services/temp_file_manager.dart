import 'dart:io';
import 'package:path_provider/path_provider.dart';

class TempFileManager {
  static Future<void> deleteFile(File file) async {
    try {
      if (await file.exists()) {
        await file.delete();
      }
    } catch (_) {}
  }

  static Future<void> cleanupOldFiles() async {
    try {
      final tempDir = await getTemporaryDirectory();
      final files = await tempDir.list().toList();
      for (final file in files) {
        if (file is File && file.path.contains('instaembed_')) {
          await file.delete();
        }
      }
    } catch (_) {}
  }
}
