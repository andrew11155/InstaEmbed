import 'dart:io';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';
import 'package:path/path.dart' as p;

class VideoDownloader {
  static const _compressorChannel = MethodChannel('instaembed/compressor');
  static const int discordMaxBytes = 20 * 1024 * 1024;
  static const int compressionThresholdBytes = 19 * 1024 * 1024;
  static const int targetCompressedBytes = 18 * 1024 * 1024;

  static Future<File> downloadFile(String url, String shortcode) async {
    final tempDir = await getTemporaryDirectory();
    final filePath = p.join(tempDir.path, 'instaembed_$shortcode.mp4');

    final response = await http.get(
      Uri.parse(url),
      headers: {
        'User-Agent':
            'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
      },
    );

    if (response.statusCode != 200) {
      throw Exception('Download failed with status ${response.statusCode}');
    }

    final file = File(filePath);
    await file.writeAsBytes(response.bodyBytes);
    return file;
  }

  static Future<File> compressIfNeeded(File input, String shortcode) async {
    final size = await input.length();
    if (size <= compressionThresholdBytes) {
      return input;
    }

    try {
      final result = await _compressorChannel.invokeMethod<Map<Object?, Object?>>(
        'compressVideo',
        {
          'inputPath': input.path,
          'shortcode': shortcode,
          'targetSizeBytes': targetCompressedBytes,
        },
      );

      if (result != null && result['success'] == true) {
        final compressedPath = result['path'] as String?;
        if (compressedPath != null) {
          final compressedFile = File(compressedPath);
          if (await compressedFile.exists() && await compressedFile.length() > 0) {
            return compressedFile;
          }
        }
      }
    } catch (_) {}

    return input;
  }

  static String formatFileSize(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }
}
