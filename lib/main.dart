import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:share_plus/share_plus.dart';
import 'screens/share_screen.dart';
import 'screens/settings_screen.dart';
import 'services/instagram_fetcher.dart';
import 'services/video_downloader.dart';
import 'services/temp_file_manager.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  TempFileManager.cleanupOldFiles();
  runApp(const InstaEmbedApp());
}

class InstaEmbedApp extends StatelessWidget {
  const InstaEmbedApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'InstaEmbed',
      theme: ThemeData(
        colorSchemeSeed: Colors.blue,
        useMaterial3: true,
      ),
      home: const HomeScreen(),
    );
  }
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  static const _channel = MethodChannel('com.instaembed/share');
  final TextEditingController _urlController = TextEditingController();
  bool _processingShare = false;

  @override
  void initState() {
    super.initState();
    _channel.setMethodCallHandler(_handleMethodCall);
    _checkInitialShare();
  }

  Future<void> _checkInitialShare() async {
    try {
      final result = await _channel.invokeMethod<String>('getInitialShare');
      if (result != null && mounted) {
        _processShareHeadless(result);
      }
    } catch (_) {}
  }

  Future<dynamic> _handleMethodCall(MethodCall call) async {
    if (call.method == 'onSharedText' && mounted) {
      final text = call.arguments as String?;
      if (text != null) _processShareHeadless(text);
    }
  }

  Future<void> _processShareHeadless(String text) async {
    final shortcode = InstagramFetcher.extractShortcode(text);
    if (shortcode == null) return;

    setState(() => _processingShare = true);

    try {
      final postData = await InstagramFetcher.fetchPost(text);
      final mediaUrl = postData.isVideo ? postData.videoUrl : postData.imageUrl;
      if (mediaUrl == null) throw Exception('No downloadable media');

      final file = await VideoDownloader.downloadFile(mediaUrl, postData.shortcode);
      if (!mounted) return;

      final result = await SharePlus.instance.share(
        ShareParams(files: [XFile(file.path)]),
      );

      await TempFileManager.deleteFile(file);

      await _channel.invokeMethod('finishActivity');
    } catch (_) {
      await _channel.invokeMethod('finishActivity');
    }
  }

  void _openShareScreen(String url) {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => ShareScreen(instagramUrl: url),
      ),
    );
  }

  void _openManualUrl() {
    final url = _urlController.text.trim();
    if (url.isNotEmpty && InstagramFetcher.extractShortcode(url) != null) {
      _openShareScreen(url);
      _urlController.clear();
    }
  }

  @override
  void dispose() {
    _urlController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (_processingShare) {
      return const Scaffold(
        body: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              CircularProgressIndicator(),
              SizedBox(height: 16),
              Text('Preparing video...'),
            ],
          ),
        ),
      );
    }

    return Scaffold(
      appBar: AppBar(
        title: const Text('InstaEmbed'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        actions: [
          IconButton(
            icon: const Icon(Icons.settings),
            onPressed: () {
              Navigator.of(context).push(
                MaterialPageRoute(builder: (_) => const SettingsScreen()),
              );
            },
          ),
        ],
      ),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Spacer(),
            const Icon(Icons.download_rounded, size: 80, color: Colors.blue),
            const SizedBox(height: 16),
            const Text(
              'InstaEmbed',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 28, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            Text(
              'Share Instagram videos to Discord\nwithout proxy services',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 16, color: Colors.grey[600]),
            ),
            const SizedBox(height: 32),
            const Text(
              'Or paste a URL manually:',
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _urlController,
              decoration: InputDecoration(
                hintText: 'https://www.instagram.com/reel/...',
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
                suffixIcon: IconButton(
                  icon: const Icon(Icons.arrow_forward),
                  onPressed: _openManualUrl,
                ),
              ),
              keyboardType: TextInputType.url,
              onSubmitted: (_) => _openManualUrl(),
            ),
            const SizedBox(height: 16),
            Text(
              'Tip: Share an Instagram link from the Instagram app\nand select InstaEmbed from the share menu',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 12, color: Colors.grey[500]),
            ),
            const Spacer(flex: 2),
          ],
        ),
      ),
    );
  }
}
