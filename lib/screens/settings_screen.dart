import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:package_info_plus/package_info_plus.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  static const _updaterChannel = MethodChannel('instaembed/updater');

  String _version = '';
  bool _checkingForUpdate = false;

  @override
  void initState() {
    super.initState();
    _loadVersion();
  }

  Future<void> _loadVersion() async {
    final info = await PackageInfo.fromPlatform();
    if (mounted) setState(() => _version = info.version);
  }

  Future<void> _checkForUpdate() async {
    setState(() => _checkingForUpdate = true);
    try {
      final result = await _updaterChannel.invokeMethod<Map<Object?, Object?>>(
        'checkForUpdateNow',
      );
      final available = result?['available'] == true;
      final version = result?['version'] as String?;
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            available
                ? 'Update v$version available — check your notifications'
                : "You're on the latest version",
          ),
        ),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Update check failed. Try again later.')),
      );
    } finally {
      if (mounted) setState(() => _checkingForUpdate = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Settings'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
      ),
      body: ListView(
        children: [
          const SizedBox(height: 16),
          Center(
            child: Column(
              children: [
                const Icon(Icons.download_rounded, size: 64, color: Colors.blue),
                const SizedBox(height: 8),
                const Text(
                  'InstaEmbed',
                  style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold),
                ),
                Text(
                  _version.isEmpty ? '' : 'v$_version',
                  style: const TextStyle(color: Colors.grey),
                ),
              ],
            ),
          ),
          const SizedBox(height: 32),
          const Divider(),
          ListTile(
            leading: const Icon(Icons.info_outline),
            title: const Text('How to use'),
            subtitle: const Text('Share Instagram links to this app'),
            onTap: () {
              showDialog(
                context: context,
                builder: (_) => AlertDialog(
                  title: const Text('How to use'),
                  content: const Text(
                    '1. Open Instagram and find a video or reel\n'
                    '2. Tap the Share button\n'
                    '3. Select InstaEmbed from the share sheet\n'
                    '4. Tap "Share to Discord" and pick your channel\n'
                    '5. The video is sent from your account!',
                  ),
                  actions: [
                    TextButton(
                      onPressed: () => Navigator.pop(context),
                      child: const Text('Got it'),
                    ),
                  ],
                ),
              );
            },
          ),
          const Divider(),
          ListTile(
            leading: _checkingForUpdate
                ? const SizedBox(
                    width: 24,
                    height: 24,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.system_update),
            title: const Text('Check for Updates'),
            subtitle: const Text('Check GitHub for a newer version'),
            onTap: _checkingForUpdate ? null : _checkForUpdate,
          ),
          const Divider(),
          const ListTile(
            leading: Icon(Icons.code),
            title: Text('No accounts required'),
            subtitle: Text('Works entirely on your device'),
          ),
        ],
      ),
    );
  }
}
