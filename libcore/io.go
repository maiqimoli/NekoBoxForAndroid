package libcore

import (
	"archive/zip"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"strings"

	"github.com/ulikunitz/xz"
)

// pathPublication keeps the previous destination until its caller has also
// committed any associated metadata (for example an asset version marker).
type pathPublication struct {
	destination string
	backup      string
}

var removePublicationBackup = os.RemoveAll

// stageFile writes content to a temporary sibling of destination. Keeping the
// staged file in the destination directory makes the later rename atomic on
// filesystems that provide atomic rename semantics.
func stageFile(destination string, content []byte, mode os.FileMode) (stagedPath string, err error) {
	file, err := os.CreateTemp(filepath.Dir(destination), "."+filepath.Base(destination)+".staged-*")
	if err != nil {
		return "", err
	}
	stagedPath = file.Name()
	cleanup := true
	defer func() {
		if cleanup {
			_ = os.Remove(stagedPath)
		}
	}()

	if err = file.Chmod(mode); err != nil {
		return "", errors.Join(err, file.Close())
	}
	_, writeErr := file.Write(content)
	closeErr := file.Close()
	if err = errors.Join(writeErr, closeErr); err != nil {
		return "", err
	}
	cleanup = false
	return stagedPath, nil
}

func publishStagedPath(stagedPath, destination string) (*pathPublication, error) {
	publication := &pathPublication{destination: destination}
	if _, err := os.Lstat(destination); err == nil {
		backup, err := reserveSiblingPath(destination, ".backup-*")
		if err != nil {
			return nil, fmt.Errorf("reserve backup path: %w", err)
		}
		if err := os.Rename(destination, backup); err != nil {
			return nil, fmt.Errorf("backup destination: %w", err)
		}
		publication.backup = backup
	} else if !os.IsNotExist(err) {
		return nil, fmt.Errorf("inspect destination: %w", err)
	}

	if err := os.Rename(stagedPath, destination); err != nil {
		var restoreErr error
		if publication.backup != "" {
			restoreErr = os.Rename(publication.backup, destination)
		}
		return nil, errors.Join(fmt.Errorf("publish staged path: %w", err), restoreErr)
	}
	return publication, nil
}

// commit finalizes an already-published path. Removing the backup is best-effort:
// once destination is live, a cleanup failure must not be reported as a failed
// publication because callers may then skip publishing associated metadata.
func (p *pathPublication) commit() {
	if p == nil || p.backup == "" {
		return
	}
	if err := removePublicationBackup(p.backup); err != nil {
		log.Printf("cleanup committed publication backup %q: %v", p.backup, err)
		return
	}
	p.backup = ""
}

func (p *pathPublication) rollback() error {
	if p == nil {
		return nil
	}
	removeErr := os.RemoveAll(p.destination)
	if p.backup == "" {
		return removeErr
	}
	if removeErr != nil {
		return removeErr
	}
	restoreErr := os.Rename(p.backup, p.destination)
	if restoreErr == nil {
		p.backup = ""
	}
	return restoreErr
}

func reserveSiblingPath(path, suffixPattern string) (string, error) {
	file, err := createSiblingTemp(path, suffixPattern)
	if err != nil {
		return "", err
	}
	temporaryPath := file.Name()
	if err := errors.Join(file.Close(), os.Remove(temporaryPath)); err != nil {
		return "", err
	}
	return temporaryPath, nil
}

func createSiblingTemp(path, suffixPattern string) (*os.File, error) {
	return os.CreateTemp(filepath.Dir(path), "."+filepath.Base(path)+suffixPattern)
}

func Unxz(archive string, path string) error {
	output, err := createSiblingTemp(path, ".unxz-*")
	if err != nil {
		return err
	}
	stagedPath := output.Name()
	defer os.Remove(stagedPath)

	if err := unxzToFile(archive, output); err != nil {
		return errors.Join(err, output.Close())
	}
	if err := output.Close(); err != nil {
		return err
	}

	publication, err := publishStagedPath(stagedPath, path)
	if err != nil {
		return err
	}
	publication.commit()
	return nil
}

// unxzToFile writes an archive into a caller-owned temporary file and makes
// the complete output durable before it can be closed and published by rename.
func unxzToFile(archive string, output *os.File) error {
	input, err := os.Open(archive)
	if err != nil {
		return err
	}
	reader, err := xz.NewReader(input)
	if err != nil {
		return errors.Join(err, input.Close())
	}
	_, copyErr := io.Copy(output, reader)
	syncErr := output.Sync()
	return errors.Join(copyErr, syncErr, input.Close())
}

func Unzip(archive string, path string) (err error) {
	reader, err := zip.OpenReader(archive)
	if err != nil {
		return err
	}
	defer func() {
		err = errors.Join(err, reader.Close())
	}()

	root, err := filepath.Abs(path)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(root, 0755); err != nil {
		return err
	}

	for _, file := range reader.File {
		cleanName := filepath.Clean(filepath.FromSlash(file.Name))
		if cleanName == "." || filepath.IsAbs(cleanName) {
			return fmt.Errorf("invalid zip entry path %q", file.Name)
		}
		filePath := filepath.Join(root, cleanName)
		relativePath, err := filepath.Rel(root, filePath)
		if err != nil || relativePath == ".." || strings.HasPrefix(relativePath, ".."+string(os.PathSeparator)) {
			return fmt.Errorf("zip entry escapes destination: %q", file.Name)
		}

		if file.FileInfo().IsDir() {
			if err := os.MkdirAll(filePath, 0755); err != nil {
				return err
			}
			continue
		}
		if !file.Mode().IsRegular() {
			return fmt.Errorf("unsupported zip entry type: %q", file.Name)
		}
		if err := os.MkdirAll(filepath.Dir(filePath), 0755); err != nil {
			return err
		}

		zipFile, err := file.Open()
		if err != nil {
			return err
		}
		mode := file.Mode().Perm()
		if mode == 0 {
			mode = 0600
		}
		newFile, err := os.OpenFile(filePath, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, mode)
		if err != nil {
			_ = zipFile.Close()
			return err
		}
		_, copyErr := io.Copy(newFile, zipFile)
		closeErr := errors.Join(zipFile.Close(), newFile.Close())
		if err := errors.Join(copyErr, closeErr); err != nil {
			_ = os.Remove(filePath)
			return err
		}
	}

	return nil
}
