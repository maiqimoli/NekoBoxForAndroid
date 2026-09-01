//go:build android

package libcore

import (
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"path"
	"path/filepath"
	"strconv"
	"strings"

	"golang.org/x/mobile/asset"
)

func extractAssets() error {
	useOfficialAssets := intfNB4A.UseOfficialAssets()
	var extractErr error
	for _, name := range []string{geoipDat, geositeDat, yacdDstFolder} {
		if err := extractAssetName(name, useOfficialAssets); err != nil {
			log.Println("Extract", name, "failed:", err)
			extractErr = errors.Join(extractErr, fmt.Errorf("extract %s: %w", name, err))
		}
	}
	return extractErr
}

// extractAssetName publishes the asset and its version marker as one logical
// update. Both files are prepared first, and the old asset remains available
// until the new version marker has also been published.
func extractAssetName(name string, useOfficialAssets bool) error {
	replaceable := true
	var version, apkPrefix string
	switch name {
	case geoipDat:
		version = geoipVersion
		apkPrefix = apkAssetPrefixSingBox
	case geositeDat:
		version = geositeVersion
		apkPrefix = apkAssetPrefixSingBox
	case yacdDstFolder:
		version = yacdVersion
		replaceable = false
	default:
		return fmt.Errorf("unknown bundled asset %q", name)
	}

	directory := externalAssetsPath
	if !replaceable {
		directory = internalAssetsPath
	}
	if directory == "" {
		return fmt.Errorf("asset directory for %q is empty", name)
	}
	if err := os.MkdirAll(directory, 0755); err != nil {
		return fmt.Errorf("create asset directory: %w", err)
	}

	destination := filepath.Join(directory, name)
	versionPath := filepath.Join(directory, version)
	assetVersion, err := readBundledAsset(path.Join(apkPrefix, version))
	if err != nil {
		return fmt.Errorf("read bundled version: %w", err)
	}
	assetVersion = []byte(strings.TrimSpace(string(assetVersion)))
	if len(assetVersion) == 0 {
		return errors.New("bundled asset version is empty")
	}

	doExtract, err := assetNeedsExtraction(destination, versionPath, name, string(assetVersion), replaceable, useOfficialAssets)
	if err != nil {
		return err
	}
	if !doExtract {
		return nil
	}

	stagedAsset, cleanupAsset, err := stageBundledAsset(name, apkPrefix, directory, destination)
	if err != nil {
		return err
	}
	defer cleanupAsset()

	stagedVersion, err := stageFile(versionPath, assetVersion, 0600)
	if err != nil {
		return fmt.Errorf("stage version marker: %w", err)
	}
	defer os.Remove(stagedVersion)

	assetPublication, err := publishStagedPath(stagedAsset, destination)
	if err != nil {
		return fmt.Errorf("publish asset: %w", err)
	}
	versionPublication, err := publishStagedPath(stagedVersion, versionPath)
	if err != nil {
		rollbackErr := assetPublication.rollback()
		return errors.Join(fmt.Errorf("publish version marker: %w", err), rollbackErr)
	}

	assetPublication.commit()
	versionPublication.commit()
	log.Println("Extract >>", destination)
	return nil
}

func assetNeedsExtraction(destination, versionPath, name, assetVersion string, replaceable, useOfficialAssets bool) (bool, error) {
	info, err := os.Stat(destination)
	if err != nil {
		if os.IsNotExist(err) {
			return true, nil
		}
		return false, fmt.Errorf("inspect installed asset: %w", err)
	}
	if (name == yacdDstFolder && !info.IsDir()) || (name != yacdDstFolder && !info.Mode().IsRegular()) {
		return true, nil
	}

	if !useOfficialAssets && replaceable {
		return false, nil
	}
	localVersionBytes, err := os.ReadFile(versionPath)
	if err != nil {
		if os.IsNotExist(err) {
			return true, nil
		}
		return false, fmt.Errorf("read installed version: %w", err)
	}
	localVersion := strings.TrimSpace(string(localVersionBytes))
	if localVersion == "Custom" {
		return false, nil
	}

	bundledNumber, bundledNumberErr := strconv.ParseUint(assetVersion, 10, 64)
	if bundledNumberErr != nil {
		return assetVersion != localVersion, nil
	}
	localNumber, localNumberErr := strconv.ParseUint(localVersion, 10, 64)
	return localNumberErr != nil || bundledNumber > localNumber, nil
}

func stageBundledAsset(name, apkPrefix, directory, destination string) (stagedPath string, cleanup func(), err error) {
	switch name {
	case geoipDat, geositeDat:
		compressedPath, err := reserveSiblingPath(destination, ".archive-*")
		if err != nil {
			return "", nil, fmt.Errorf("reserve compressed asset path: %w", err)
		}
		stagedPath, err := reserveSiblingPath(destination, ".staged-*")
		if err != nil {
			return "", nil, fmt.Errorf("reserve staged asset path: %w", err)
		}
		cleanup = func() {
			_ = os.Remove(compressedPath)
			_ = os.Remove(stagedPath)
		}
		assetFile, err := asset.Open(path.Join(apkPrefix, name+".xz"))
		if err != nil {
			cleanup()
			return "", nil, fmt.Errorf("open compressed bundled asset: %w", err)
		}
		if err := extractAsset(assetFile, compressedPath); err != nil {
			cleanup()
			return "", nil, fmt.Errorf("copy compressed bundled asset: %w", err)
		}
		if err := Unxz(compressedPath, stagedPath); err != nil {
			cleanup()
			return "", nil, fmt.Errorf("decompress bundled asset: %w", err)
		}
		if err := os.Remove(compressedPath); err != nil && !os.IsNotExist(err) {
			cleanup()
			return "", nil, fmt.Errorf("remove staged archive: %w", err)
		}
		return stagedPath, cleanup, nil

	case yacdDstFolder:
		archivePath, err := reserveSiblingPath(destination, ".archive-*")
		if err != nil {
			return "", nil, fmt.Errorf("reserve dashboard archive path: %w", err)
		}
		extractRoot, err := os.MkdirTemp(directory, ".yacd-staged-*")
		if err != nil {
			return "", nil, fmt.Errorf("create dashboard staging directory: %w", err)
		}
		cleanup = func() {
			_ = os.Remove(archivePath)
			_ = os.RemoveAll(extractRoot)
		}
		assetFile, err := asset.Open("yacd.zip")
		if err != nil {
			cleanup()
			return "", nil, fmt.Errorf("open bundled dashboard: %w", err)
		}
		if err := extractAsset(assetFile, archivePath); err != nil {
			cleanup()
			return "", nil, fmt.Errorf("copy bundled dashboard: %w", err)
		}
		if err := Unzip(archivePath, extractRoot); err != nil {
			cleanup()
			return "", nil, fmt.Errorf("decompress bundled dashboard: %w", err)
		}
		if err := os.Remove(archivePath); err != nil && !os.IsNotExist(err) {
			cleanup()
			return "", nil, fmt.Errorf("remove staged archive: %w", err)
		}
		matches, err := filepath.Glob(filepath.Join(extractRoot, "Yacd-*"))
		if err != nil {
			cleanup()
			return "", nil, fmt.Errorf("find dashboard directory: %w", err)
		}
		if len(matches) != 1 {
			cleanup()
			return "", nil, fmt.Errorf("found %d dashboard directories, expected 1", len(matches))
		}
		info, err := os.Lstat(matches[0])
		if err != nil {
			cleanup()
			return "", nil, fmt.Errorf("inspect dashboard directory: %w", err)
		}
		if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			cleanup()
			return "", nil, errors.New("bundled dashboard root is not a regular directory")
		}
		return matches[0], cleanup, nil
	default:
		return "", nil, fmt.Errorf("unsupported bundled asset %q", name)
	}
}

func readBundledAsset(name string) ([]byte, error) {
	assetFile, err := asset.Open(name)
	if err != nil {
		return nil, err
	}
	content, readErr := io.ReadAll(assetFile)
	closeErr := assetFile.Close()
	if err := errors.Join(readErr, closeErr); err != nil {
		return nil, err
	}
	return content, nil
}

func extractAsset(input asset.File, destination string) error {
	output, err := os.OpenFile(destination, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if err != nil {
		return errors.Join(err, input.Close())
	}
	_, copyErr := io.Copy(output, input)
	closeErr := errors.Join(output.Close(), input.Close())
	if err := errors.Join(copyErr, closeErr); err != nil {
		_ = os.Remove(destination)
		return err
	}
	return nil
}
