use flate2::read::GzDecoder;
use sha2::{Digest, Sha256};
use std::fs;
use std::io::Read;
use std::path::{Component, Path, PathBuf};
use tar::Archive;
use tempfile::TempDir;

pub fn fetch_text(url: &str, max_bytes: u64) -> Result<String, String> {
    require_https(url)?;
    let response = ureq::get(url).call().map_err(|error| error.to_string())?;
    let mut reader = response.into_body().into_reader();
    let bytes = read_limited(&mut reader, max_bytes)?;
    String::from_utf8(bytes).map_err(|error| error.to_string())
}

pub fn ensure_package(
    specification: &str,
    archive_url: &str,
    expected_sha256: Option<&str>,
    max_bytes: u64,
    cache_root: &Path,
) -> Result<PathBuf, String> {
    let (namespace, name, version) = parse_specification(specification)?;
    if namespace != "preview" {
        return Err("only @preview packages may be downloaded".to_owned());
    }
    require_https(archive_url)?;
    let destination = cache_root.join(namespace).join(name).join(version);
    if destination.is_dir() {
        return Ok(destination);
    }

    let response = ureq::get(archive_url)
        .call()
        .map_err(|error| error.to_string())?;
    let mut reader = response.into_body().into_reader();
    let bytes = read_limited(&mut reader, max_bytes)?;
    if let Some(expected) = expected_sha256 {
        let actual = format!("{:x}", Sha256::digest(&bytes));
        if !actual.eq_ignore_ascii_case(expected) {
            return Err(format!(
                "package SHA-256 mismatch: expected {expected}, got {actual}"
            ));
        }
    }

    let parent = destination
        .parent()
        .ok_or_else(|| "package destination has no parent".to_owned())?;
    fs::create_dir_all(parent).map_err(|error| error.to_string())?;
    let staging = TempDir::new_in(parent).map_err(|error| error.to_string())?;
    unpack_secure(&bytes, staging.path())?;
    reject_symlinks(staging.path())?;
    match fs::rename(staging.path(), &destination) {
        Ok(()) => {
            std::mem::forget(staging);
            Ok(destination)
        }
        Err(_error) if destination.is_dir() => Ok(destination),
        Err(error) => Err(error.to_string()),
    }
}

fn unpack_secure(bytes: &[u8], destination: &Path) -> Result<(), String> {
    let decoder = GzDecoder::new(bytes);
    let mut archive = Archive::new(decoder);
    for entry in archive.entries().map_err(|error| error.to_string())? {
        let mut entry = entry.map_err(|error| error.to_string())?;
        let path = entry.path().map_err(|error| error.to_string())?;
        validate_relative_path(&path)?;
        let kind = entry.header().entry_type();
        if kind.is_symlink() || kind.is_hard_link() {
            return Err(format!(
                "package archive contains a link: {}",
                path.display()
            ));
        }
        entry
            .unpack_in(destination)
            .map_err(|error| error.to_string())?;
    }
    Ok(())
}

fn validate_relative_path(path: &Path) -> Result<(), String> {
    if path.is_absolute()
        || path.components().any(|component| {
            matches!(
                component,
                Component::ParentDir | Component::RootDir | Component::Prefix(_)
            )
        })
    {
        return Err(format!(
            "package archive path escapes destination: {}",
            path.display()
        ));
    }
    Ok(())
}

fn reject_symlinks(root: &Path) -> Result<(), String> {
    let mut pending = vec![root.to_owned()];
    while let Some(directory) = pending.pop() {
        for entry in fs::read_dir(directory).map_err(|error| error.to_string())? {
            let entry = entry.map_err(|error| error.to_string())?;
            let metadata = fs::symlink_metadata(entry.path()).map_err(|error| error.to_string())?;
            if metadata.file_type().is_symlink() {
                return Err(format!(
                    "package contains a symbolic link: {}",
                    entry.path().display()
                ));
            }
            if metadata.is_dir() {
                pending.push(entry.path());
            }
        }
    }
    Ok(())
}

fn read_limited(reader: &mut impl Read, max_bytes: u64) -> Result<Vec<u8>, String> {
    let mut bytes = Vec::new();
    reader
        .take(max_bytes.saturating_add(1))
        .read_to_end(&mut bytes)
        .map_err(|error| error.to_string())?;
    if bytes.len() as u64 > max_bytes {
        return Err(format!("download exceeds {max_bytes} byte limit"));
    }
    Ok(bytes)
}

fn require_https(raw_url: &str) -> Result<(), String> {
    let url = url::Url::parse(raw_url).map_err(|error| error.to_string())?;
    if url.scheme() != "https" {
        return Err("only HTTPS package URLs are allowed".to_owned());
    }
    Ok(())
}

fn parse_specification(specification: &str) -> Result<(&str, &str, &str), String> {
    let specification = specification
        .strip_prefix('@')
        .ok_or_else(|| "package specification must start with @".to_owned())?;
    let (namespace, rest) = specification
        .split_once('/')
        .ok_or_else(|| "package specification needs a namespace".to_owned())?;
    let (name, version) = rest
        .split_once(':')
        .ok_or_else(|| "package specification needs a version".to_owned())?;
    if [namespace, name, version].iter().any(|part| {
        part.is_empty()
            || !part
                .chars()
                .all(|character| character.is_ascii_alphanumeric() || "-_+.".contains(character))
    }) {
        return Err("package specification contains invalid characters".to_owned());
    }
    Ok((namespace, name, version))
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::write::GzEncoder;
    use flate2::Compression;
    use tar::{Builder, Header};

    #[test]
    fn rejects_parent_directory_archive_path() {
        assert!(validate_relative_path(Path::new("../escape")).is_err());
        assert!(validate_relative_path(Path::new("package/typst.toml")).is_ok());
    }

    #[test]
    fn rejects_non_preview_namespace() {
        let root = tempfile::tempdir().unwrap();
        let error = ensure_package(
            "@local/demo:1.0.0",
            "https://example.invalid/a.tar.gz",
            None,
            10,
            root.path(),
        )
        .unwrap_err();
        assert!(error.contains("@preview"));
    }

    #[test]
    fn secure_unpack_accepts_regular_files() {
        let mut encoded = Vec::new();
        {
            let encoder = GzEncoder::new(&mut encoded, Compression::default());
            let mut builder = Builder::new(encoder);
            let content = b"[package]\nentrypoint = \"lib.typ\"";
            let mut header = Header::new_gnu();
            header.set_size(content.len() as u64);
            header.set_mode(0o644);
            header.set_cksum();
            builder
                .append_data(&mut header, "typst.toml", &content[..])
                .unwrap();
            builder.finish().unwrap();
        }
        let root = tempfile::tempdir().unwrap();
        unpack_secure(&encoded, root.path()).unwrap();
        assert!(root.path().join("typst.toml").is_file());
    }
}
