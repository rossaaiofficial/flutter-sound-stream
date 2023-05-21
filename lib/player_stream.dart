part of sound_stream;

enum AudioOutput { headphones, speaker }

class PlayerStream {
  factory PlayerStream() => _instance;

  PlayerStream._internal() {
    SoundStream();
    _eventsStreamController.stream.listen(_eventListener);
    _playerStatusController.add(SoundStreamStatus.Unset);
    _audioStreamController.stream.listen((data) {
      writeChunk(data);
    });
  }

  static final PlayerStream _instance = PlayerStream._internal();

  final _audioStreamController = StreamController<Uint8List>();
  final _playerStatusController =
      StreamController<SoundStreamStatus>.broadcast();

  /// Initialize Player with specified [sampleRate]
  Future<dynamic> initialize({int sampleRate = 16000, bool showLogs = false}) =>
      _methodChannel.invokeMethod("initializePlayer", {
        "sampleRate": sampleRate,
        "showLogs": showLogs,
      });

  /// Player will start receiving audio chunks (PCM 16bit data)
  /// to audiostream as Uint8List to play audio.
  Future<dynamic> start() => _methodChannel.invokeMethod("startPlayer");

  /// Player will stop receiving audio chunks.
  Future<dynamic> stop() => _methodChannel.invokeMethod("stopPlayer");

  /// Push audio [data] (PCM 16bit data) to player buffer as Uint8List
  /// to play audio. Chunks will be queued/scheduled to play sequentially
  Future<dynamic> writeChunk(Uint8List data) => _methodChannel
      .invokeMethod("writeChunk", <String, dynamic>{"data": data});

  Future<void> setAudioOutput(AudioOutput audioOutput) {
    return _methodChannel.invokeMethod("setAudioOutput", {
      "audioOutput": _enumToString(audioOutput),
    });
  }

  /// Current status of the [PlayerStream]
  Stream<SoundStreamStatus> get status => _playerStatusController.stream;

  /// Stream's sink to receive PCM 16bit data to send to Player
  StreamSink<Uint8List> get audioStream => _audioStreamController.sink;

  /// Stop and close all streams. This cannot be undone
  /// Only call this method if you don't want to use this anymore
  void dispose() {
    stop();
    _eventsStreamController.close();
    _playerStatusController.close();
    _audioStreamController.close();
  }

  void _eventListener(dynamic event) {
    if (event == null) return;
    final String eventName = event["name"] ?? "";
    switch (eventName) {
      case "playerStatus":
        final String status = event["data"] ?? "Unset";
        _playerStatusController.add(SoundStreamStatus.values.firstWhere(
          (value) => _enumToString(value) == status,
          orElse: () => SoundStreamStatus.Unset,
        ));
        break;
    }
  }
}
