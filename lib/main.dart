import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'screens/share_screen.dart';
import 'screens/settings_screen.dart';
import 'screens/video_share_screen.dart';
import 'services/instagram_fetcher.dart';

void main() {
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
  static const _compressorChannel = MethodChannel('instaembed/compressor');
  final TextEditingController _urlController = TextEditingController();

  void _openShareScreen() {
    final url = _urlController.text.trim();
    if (url.isNotEmpty && InstagramFetcher.extractShortcode(url) != null) {
      Navigator.of(context).push(
        MaterialPageRoute(
          builder: (_) => ShareScreen(instagramUrl: url),
        ),
      );
      _urlController.clear();
    }
  }

  Future<void> _pickVideo() async {
    try {
      final result = await _compressorChannel.invokeMethod<Map<Object?, Object?>>('pickVideo');
      if (result != null && mounted) {
        final path = result['path'] as String?;
        final name = result['name'] as String? ?? 'video.mp4';
        final size = (result['size'] as num?)?.toInt() ?? 0;
        if (path != null) {
          Navigator.of(context).push(
            MaterialPageRoute(
              builder: (_) => VideoShareScreen(
                filePath: path,
                fileName: name,
                fileSize: size,
              ),
            ),
          );
        }
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to select video: $e')),
        );
      }
    }
  }

  @override
  void dispose() {
    _urlController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
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
              'Share Instagram & local videos to Discord\ncompressed under 20MB',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 16, color: Colors.grey[600]),
            ),
            const SizedBox(height: 32),
            const Text(
              'Paste an Instagram URL:',
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
                  onPressed: _openShareScreen,
                ),
              ),
              keyboardType: TextInputType.url,
              onSubmitted: (_) => _openShareScreen(),
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(child: Divider(color: Colors.grey[300])),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  child: Text('OR', style: TextStyle(color: Colors.grey[500], fontSize: 12)),
                ),
                Expanded(child: Divider(color: Colors.grey[300])),
              ],
            ),
            const SizedBox(height: 16),
            OutlinedButton.icon(
              onPressed: _pickVideo,
              icon: const Icon(Icons.video_library_rounded),
              label: const Text('Compress & Share a Video File'),
              style: OutlinedButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 14),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
              ),
            ),
            const SizedBox(height: 20),
            Text(
              'Tip: Share a video or Instagram link from any app\nand select InstaEmbed from the share menu',
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
