import 'dart:io';
import 'package:flutter/material.dart';
import 'package:share_plus/share_plus.dart';
import '../models/post_data.dart';
import '../services/instagram_fetcher.dart';
import '../services/video_downloader.dart';
import '../services/temp_file_manager.dart';

class ShareScreen extends StatefulWidget {
  final String instagramUrl;

  const ShareScreen({super.key, required this.instagramUrl});

  @override
  State<ShareScreen> createState() => _ShareScreenState();
}

enum _ScreenState { loading, preview, error, sharing, done }

class _ShareScreenState extends State<ShareScreen> {
  _ScreenState _state = _ScreenState.loading;
  PostData? _postData;
  File? _downloadedFile;
  String? _errorMsg;

  @override
  void initState() {
    super.initState();
    _fetchPost();
  }

  Future<void> _fetchPost() async {
    setState(() {
      _state = _ScreenState.loading;
      _errorMsg = null;
    });

    try {
      final data = await InstagramFetcher.fetchPost(widget.instagramUrl);
      final mediaUrl = data.isVideo ? data.videoUrl : data.imageUrl;
      if (mediaUrl == null) throw Exception('No downloadable media');
      final file = await VideoDownloader.downloadFile(mediaUrl, data.shortcode);
      if (!mounted) return;
      setState(() {
        _postData = data;
        _downloadedFile = file;
        _state = _ScreenState.preview;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _errorMsg = e.toString();
        _state = _ScreenState.error;
      });
    }
  }

  Future<void> _share() async {
    if (_downloadedFile == null) return;
    setState(() => _state = _ScreenState.sharing);

    try {
      await SharePlus.instance.share(
        ShareParams(files: [XFile(_downloadedFile!.path)]),
      );
    } catch (_) {}

    await TempFileManager.deleteFile(_downloadedFile!);
    if (!mounted) return;
    setState(() => _state = _ScreenState.done);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('InstaEmbed'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
      ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    switch (_state) {
      case _ScreenState.loading:
        return const Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              CircularProgressIndicator(),
              SizedBox(height: 16),
              Text('Fetching video...'),
            ],
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
              const Text('Shared successfully!'),
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
    final data = _postData!;
    final fileSize = _downloadedFile != null ? _downloadedFile!.lengthSync() : 0;

    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (data.imageUrl != null)
            ClipRRect(
              borderRadius: BorderRadius.circular(12),
              child: Image.network(
                data.imageUrl!,
                height: 300,
                fit: BoxFit.cover,
                errorBuilder: (_, __, ___) => Container(
                  height: 300,
                  color: Colors.grey[300],
                  child: const Center(child: Icon(Icons.broken_image, size: 64)),
                ),
              ),
            ),
          const SizedBox(height: 16),
          Row(
            children: [
              if (data.authorUsername != null) ...[
                CircleAvatar(
                  radius: 16,
                  backgroundImage: data.authorProfilePic != null
                      ? NetworkImage(data.authorProfilePic!)
                      : null,
                  child: data.authorProfilePic == null
                      ? const Icon(Icons.person, size: 16)
                      : null,
                ),
                const SizedBox(width: 8),
                Text(
                  '@${data.authorUsername}',
                  style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
                ),
              ],
            ],
          ),
          if (data.caption != null && data.caption!.isNotEmpty) ...[
            const SizedBox(height: 8),
            Text(
              data.caption!,
              maxLines: 3,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(color: Colors.grey[700]),
            ),
          ],
          const SizedBox(height: 8),
          Row(
            children: [
              Icon(Icons.favorite, color: Colors.red[400], size: 18),
              const SizedBox(width: 4),
              Text('${data.likeCount}'),
              const Spacer(),
              Icon(Icons.attach_file, color: Colors.grey[500], size: 18),
              const SizedBox(width: 4),
              Text(VideoDownloader.formatFileSize(fileSize)),
              const SizedBox(height: 8),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                decoration: BoxDecoration(
                  color: data.isVideo ? Colors.blue[100] : Colors.green[100],
                  borderRadius: BorderRadius.circular(4),
                ),
                child: Text(
                  data.isVideo ? 'VIDEO' : 'PHOTO',
                  style: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.bold,
                    color: data.isVideo ? Colors.blue[800] : Colors.green[800],
                  ),
                ),
              ),
            ],
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
            const Text('Failed to fetch post', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            Text(
              _errorMsg ?? 'Unknown error',
              textAlign: TextAlign.center,
              style: TextStyle(color: Colors.grey[600]),
            ),
            const SizedBox(height: 24),
            ElevatedButton.icon(
              onPressed: _fetchPost,
              icon: const Icon(Icons.refresh),
              label: const Text('Retry'),
            ),
          ],
        ),
      ),
    );
  }
}
