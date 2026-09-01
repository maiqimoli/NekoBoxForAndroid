package libcore

import (
	"archive/zip"
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/ulikunitz/xz"
)

func TestPathPublicationCommit(t *testing.T) {
	directory := t.TempDir()
	destination := filepath.Join(directory, "asset.dat")
	if err := os.WriteFile(destination, []byte("old"), 0o600); err != nil {
		t.Fatal(err)
	}
	staged, err := stageFile(destination, []byte("new"), 0o600)
	if err != nil {
		t.Fatal(err)
	}

	publication, err := publishStagedPath(staged, destination)
	if err != nil {
		t.Fatal(err)
	}
	backup := publication.backup
	if backup == "" {
		t.Fatal("existing destination was not retained as a backup")
	}
	assertFileContent(t, destination, "new")
	assertFileContent(t, backup, "old")

	publication.commit()
	if publication.backup != "" {
		t.Fatalf("backup path was not cleared after commit: %q", publication.backup)
	}
	if _, err := os.Lstat(backup); !os.IsNotExist(err) {
		t.Fatalf("backup still exists after commit: %v", err)
	}
}

func TestPathPublicationRollbackRestoresPreviousDestination(t *testing.T) {
	directory := t.TempDir()
	destination := filepath.Join(directory, "asset.dat")
	if err := os.WriteFile(destination, []byte("old"), 0o600); err != nil {
		t.Fatal(err)
	}
	staged, err := stageFile(destination, []byte("new"), 0o600)
	if err != nil {
		t.Fatal(err)
	}
	publication, err := publishStagedPath(staged, destination)
	if err != nil {
		t.Fatal(err)
	}
	backup := publication.backup

	if err := publication.rollback(); err != nil {
		t.Fatal(err)
	}
	assertFileContent(t, destination, "old")
	if _, err := os.Lstat(backup); !os.IsNotExist(err) {
		t.Fatalf("backup still exists after rollback: %v", err)
	}
}

func TestPathPublicationRollbackRemovesNewDestination(t *testing.T) {
	destination := filepath.Join(t.TempDir(), "asset.dat")
	staged, err := stageFile(destination, []byte("new"), 0o600)
	if err != nil {
		t.Fatal(err)
	}
	publication, err := publishStagedPath(staged, destination)
	if err != nil {
		t.Fatal(err)
	}
	if publication.backup != "" {
		t.Fatalf("unexpected backup for a new destination: %q", publication.backup)
	}

	if err := publication.rollback(); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Lstat(destination); !os.IsNotExist(err) {
		t.Fatalf("new destination still exists after rollback: %v", err)
	}
}

func TestUnxzPublishesCompleteOutput(t *testing.T) {
	directory := t.TempDir()
	archive := filepath.Join(directory, "asset.xz")
	destination := filepath.Join(directory, "asset.dat")
	if err := os.WriteFile(destination, []byte("old"), 0o600); err != nil {
		t.Fatal(err)
	}

	var compressed bytes.Buffer
	writer, err := xz.NewWriter(&compressed)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := writer.Write([]byte("complete new asset")); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(archive, compressed.Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}

	if err := Unxz(archive, destination); err != nil {
		t.Fatal(err)
	}
	assertFileContent(t, destination, "complete new asset")
}

func TestUnxzCorruptionPreservesPreviousDestination(t *testing.T) {
	directory := t.TempDir()
	archive := filepath.Join(directory, "asset.xz")
	destination := filepath.Join(directory, "asset.dat")
	if err := os.WriteFile(archive, []byte("not an xz stream"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(destination, []byte("old"), 0o600); err != nil {
		t.Fatal(err)
	}

	if err := Unxz(archive, destination); err == nil {
		t.Fatal("expected corrupt archive to fail")
	}
	assertFileContent(t, destination, "old")
}

func TestUnzipRejectsPathTraversal(t *testing.T) {
	directory := t.TempDir()
	archive := filepath.Join(directory, "asset.zip")
	writeZip(t, archive, []zipTestEntry{{name: "../outside.txt", content: "outside"}})
	destination := filepath.Join(directory, "output")

	err := Unzip(archive, destination)
	if err == nil || !strings.Contains(err.Error(), "escapes destination") {
		t.Fatalf("unexpected traversal result: %v", err)
	}
	if _, err := os.Lstat(filepath.Join(directory, "outside.txt")); !os.IsNotExist(err) {
		t.Fatalf("archive created a file outside the destination: %v", err)
	}
}

func TestUnzipRejectsSpecialEntries(t *testing.T) {
	directory := t.TempDir()
	archive := filepath.Join(directory, "asset.zip")
	writeZip(t, archive, []zipTestEntry{{
		name:    "link",
		content: "target",
		mode:    os.ModeSymlink | 0o777,
	}})

	err := Unzip(archive, filepath.Join(directory, "output"))
	if err == nil || !strings.Contains(err.Error(), "unsupported zip entry type") {
		t.Fatalf("unexpected special-entry result: %v", err)
	}
}

type zipTestEntry struct {
	name    string
	content string
	mode    os.FileMode
}

func writeZip(t *testing.T, path string, entries []zipTestEntry) {
	t.Helper()
	archive, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	writer := zip.NewWriter(archive)
	for _, entry := range entries {
		header := &zip.FileHeader{Name: entry.name, Method: zip.Store}
		if entry.mode != 0 {
			header.SetMode(entry.mode)
		}
		file, err := writer.CreateHeader(header)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := file.Write([]byte(entry.content)); err != nil {
			t.Fatal(err)
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if err := archive.Close(); err != nil {
		t.Fatal(err)
	}
}

func assertFileContent(t *testing.T, path, want string) {
	t.Helper()
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if got := string(content); got != want {
		t.Fatalf("%s content = %q, want %q", path, got, want)
	}
}
