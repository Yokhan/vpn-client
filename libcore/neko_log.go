package libcore

import (
	"log"
	"os"
	"sync"
)

// fileLogWriter is the small, dependency-free replacement for the old
// Matsuri neko_log package. It keeps one append-only file and truncates it
// when the configured size is exceeded.
type fileLogWriter struct {
	mu       sync.Mutex
	file     *os.File
	path     string
	maxBytes int64
}

var logWriter = &fileLogWriter{}
var logWriterDisable bool
var truncateOnStart bool

func setupLog(maxBytes int, path string) {
	logWriter.mu.Lock()
	defer logWriter.mu.Unlock()

	if logWriter.file != nil {
		_ = logWriter.file.Close()
		logWriter.file = nil
	}
	logWriter.path = path
	logWriter.maxBytes = int64(maxBytes)
	if truncateOnStart {
		_ = os.Remove(path)
	}
	file, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		log.Println("open log file:", err)
		return
	}
	logWriter.file = file
}

func (w *fileLogWriter) Write(p []byte) (int, error) {
	if logWriterDisable {
		return len(p), nil
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.file == nil {
		return len(p), nil
	}
	n, err := w.file.Write(p)
	if err != nil {
		return n, err
	}
	if w.maxBytes > 0 {
		if info, statErr := w.file.Stat(); statErr == nil && info.Size() > w.maxBytes {
			_ = w.file.Truncate(0)
			_, _ = w.file.Seek(0, 0)
		}
	}
	return n, nil
}

func (w *fileLogWriter) Truncate() {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.file == nil {
		return
	}
	_ = w.file.Truncate(0)
	_, _ = w.file.Seek(0, 0)
}
