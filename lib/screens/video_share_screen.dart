import 'dart:io';
import 'package:flutter/material.dart';
import 'package:share_plus/share_plus.dart';
import '../services/video_downloader.dart';
import '../services/temp_file_manager.dart';

class VideoShareScreen extends StatefulWidget {
  final String filePath;
  final String fileName;
  final int fileSize;

  const VideoShareScreen({
    super.key,
    required this.filePath,
    required this.fileName,
    required this.fileSize,
  });

  @override
  State<VideoShareScreen> createState() => _VideoShareScreenState();
}

enum _ScreenState { compressing, preview, sharing, done, error }

class _VideoShareScreenState extends State<VideoShareScreen> {
  _ScreenState _state = _ScreenState.preview;
  late File _inputFile;
  File? _compressedFile;
  String? _errorMsg;

  @override
  void initState() {
    super.initState();
    _inputFile = File(widget.filePath);
    _prepareVideo();
  }

  Future<void> _prepareVideo() async {
    final size = widget.fileSize > 0 ? widget.fileSize : await _inputFile.length();

    if (size > VideoDownloader.compressionThresholdBytes) {
      if (mounted) setState(() => _state = _ScreenState.compressing);
      try {
        final shortcode = 'local_${DateTime.now().millisecondsSinceEpoch}';
        final compressed = await VideoDownloader.compressIfNeeded(_inputFile, shortcode);
        if (!mounted) return;
        setState(() {
          _compressedFile = compressed;
          _state = _ScreenState.preview;
        });
      } catch (e) {
        if (!mounted) return;
        setState(() {
          _errorMsg = e.toString();
          _state = _ScreenState.error;
        });
      }
    } else {
      if (mounted) {
        setState(() => _state = _ScreenState.preview);
      }
    }
  }

  Future<void> _share() async {
    final fileToShare = _compressedFile ?? _inputFile;
    setState(() => _state = _ScreenState.sharing);

    try {
      await SharePlus.instance.share(
        ShareParams(files: [XFile(fileToShare.path)]),
      );
    } catch (_) {}

    await TempFileManager.deleteFile(_inputFile);
    if (_compressedFile != null && _compressedFile!.path != _inputFile.path) {
      await TempFileManager.deleteFile(_compressedFile!);
    }

    if (!mounted) return;
    setState(() => _state = _ScreenState.done);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Share Video'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
      ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    switch (_state) {
      case _ScreenState.compressing:
        return Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const CircularProgressIndicator(),
                const SizedBox(height: 20),
                const Text(
                  'Compressing video for Discord (<20MB)...',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.w500),
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 8),
                Text(
                  'Original size: ${VideoDownloader.formatFileSize(widget.fileSize)}',
                  style: TextStyle(color: Colors.grey[600]),
                ),
              ],
            ),
          ),
        );
      case _ScreenState.preview:
        return _buildPreview();
      case _ScreenState.error:
        return _buildError();
      case _ScreenState.sharing:
        return const Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              CircularProgressIndicator(),
              SizedBox(height: 16),
              Text('Sharing...'),
            ],
          ),
        );
      case _ScreenState.done:
        return Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.check_circle, color: Colors.green, size: 64),
              const SizedBox(height: 16),
              const Text('Shared successfully!', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
              const SizedBox(height: 24),
              ElevatedButton(
                onPressed: () => Navigator.of(context).pop(),
                child: const Text('Done'),
              ),
            ],
          ),
        );
    }
  }

  Widget _buildPreview() {
    final finalFile = _compressedFile ?? _inputFile;
    final finalSize = finalFile.existsSync() ? finalFile.lengthSync() : widget.fileSize;
    final wasCompressed = _compressedFile != null && _compressedFile!.path != _inputFile.path;

    return SingleChildScrollView(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Card(
            elevation: 2,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: Column(
                children: [
                  Container(
                    width: 72,
                    height: 72,
                    decoration: BoxDecoration(
                      color: Theme.of(context).colorScheme.primaryContainer,
                      shape: BoxShape.circle,
                    ),
                    child: Icon(
                      Icons.movie_outlined,
                      size: 36,
                      color: Theme.of(context).colorScheme.onPrimaryContainer,
                    ),
                  ),
                  const SizedBox(height: 16),
                  Text(
                    widget.fileName,
                    style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                    textAlign: TextAlign.center,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                  const SizedBox(height: 12),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                        decoration: BoxDecoration(
                          color: Colors.blue[100],
                          borderRadius: BorderRadius.circular(4),
                        ),
                        child: Text(
                          'VIDEO',
                          style: TextStyle(
                            fontSize: 11,
                            fontWeight: FontWeight.bold,
                            color: Colors.blue[800],
                          ),
                        ),
                      ),
                      if (wasCompressed) ...[
                        const SizedBox(width: 8),
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                          decoration: BoxDecoration(
                            color: Colors.orange[100],
                            borderRadius: BorderRadius.circular(4),
                          ),
                          child: Text(
                            'COMPRESSED (<20MB)',
                            style: TextStyle(
                              fontSize: 11,
                              fontWeight: FontWeight.bold,
                              color: Colors.orange[900],
                            ),
                          ),
                        ),
                      ],
                    ],
                  ),
                  const SizedBox(height: 16),
                  const Divider(),
                  const SizedBox(height: 8),
                  if (wasCompressed) ...[
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text('Original size:', style: TextStyle(color: Colors.grey[600])),
                        Text(
                          VideoDownloader.formatFileSize(widget.fileSize),
                          style: const TextStyle(decoration: TextDecoration.lineThrough),
                        ),
                      ],
                    ),
                    const SizedBox(height: 6),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        const Text('Compressed size:', style: TextStyle(fontWeight: FontWeight.bold)),
                        Text(
                          VideoDownloader.formatFileSize(finalSize),
                          style: TextStyle(
                            fontWeight: FontWeight.bold,
                            color: finalSize <= VideoDownloader.discordMaxBytes
                                ? Colors.green[700]
                                : Colors.red[700],
                          ),
                        ),
                      ],
                    ),
                  ] else ...[
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text('File size:', style: TextStyle(color: Colors.grey[600])),
                        Text(
                          VideoDownloader.formatFileSize(finalSize),
                          style: TextStyle(
                            fontWeight: FontWeight.bold,
                            color: Colors.green[700],
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 4),
                    Text(
                      'Within Discord\'s 20MB limit — no compression needed',
                      style: TextStyle(color: Colors.green[600], fontSize: 12),
                    ),
                  ],
                ],
              ),
            ),
          ),
          const SizedBox(height: 24),
          ElevatedButton.icon(
            onPressed: _share,
            icon: const Icon(Icons.send),
            label: const Text('Share to Discord'),
            style: ElevatedButton.styleFrom(
              padding: const EdgeInsets.symmetric(vertical: 16),
              textStyle: const TextStyle(fontSize: 16),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildError() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.error_outline, color: Colors.red, size: 64),
            const SizedBox(height: 16),
            const Text('Compression failed', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            Text(
              _errorMsg ?? 'Unknown error occurred',
              textAlign: TextAlign.center,
              style: TextStyle(color: Colors.grey[600]),
            ),
            const SizedBox(height: 24),
            ElevatedButton.icon(
              onPressed: _prepareVideo,
              icon: const Icon(Icons.refresh),
              label: const Text('Retry Compression'),
            ),
            const SizedBox(height: 8),
            TextButton(
              onPressed: _share,
              child: const Text('Share Original Video Anyway'),
            ),
          ],
        ),
      ),
    );
  }
}
