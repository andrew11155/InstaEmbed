class PostData {
  final String shortcode;
  final String? videoUrl;
  final String? imageUrl;
  final String? caption;
  final String? authorUsername;
  final String? authorProfilePic;
  final int likeCount;

  PostData({
    required this.shortcode,
    this.videoUrl,
    this.imageUrl,
    this.caption,
    this.authorUsername,
    this.authorProfilePic,
    this.likeCount = 0,
  });

  bool get isVideo => videoUrl != null;
}
