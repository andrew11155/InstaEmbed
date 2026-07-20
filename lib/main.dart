import 'package:flutter/material.dart';
import 'screens/share_screen.dart';
import 'screens/settings_screen.dart';
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
                  onPressed: _openShareScreen,
                ),
              ),
              keyboardType: TextInputType.url,
              onSubmitted: (_) => _openShareScreen(),
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
