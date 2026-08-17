package libcore

import "errors"

func duplicateFileDescriptor(int) (int, error) {
	return -1, errors.New("file descriptor duplication is unavailable on Windows")
}

func sendFdToProtect(int, string) error {
	return errors.New("file descriptor protection is unavailable on Windows")
}
