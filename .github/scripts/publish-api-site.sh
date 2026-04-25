#!/usr/bin/env bash

set -euo pipefail

if [[ -z "${RELEASE_TAG:-}" ]]; then
  echo "RELEASE_TAG is required" >&2
  exit 1
fi

if [[ -z "${REPOSITORY:-}" ]]; then
  echo "REPOSITORY is required" >&2
  exit 1
fi

if [[ -z "${PAGES_DEPLOY_TOKEN:-}" ]]; then
  echo "PAGES_DEPLOY_TOKEN is required" >&2
  exit 1
fi

site_dir="${SITE_DIR:-site}"
repo_url="https://x-access-token:${PAGES_DEPLOY_TOKEN}@github.com/${REPOSITORY}.git"

export SITE_DIR="${site_dir}"
export API_DOCS_SOURCE_DIR="${API_DOCS_SOURCE_DIR:-doc/api}"

rm -rf "${site_dir}"

ls_remote_error="$(mktemp)"
if git ls-remote --exit-code --heads "${repo_url}" gh-pages >/dev/null 2>"${ls_remote_error}"; then
  rm -f "${ls_remote_error}"
  git clone --depth 1 --branch gh-pages "${repo_url}" "${site_dir}"
else
  ls_remote_status=$?
  if [[ "${ls_remote_status}" -eq 2 ]]; then
    rm -f "${ls_remote_error}"
    mkdir -p "${site_dir}"
    git -C "${site_dir}" init --initial-branch=gh-pages
    git -C "${site_dir}" remote add origin "${repo_url}"
  else
    echo "Cannot query gh-pages branch. Check PAGES_DEPLOY_TOKEN and repository access." >&2
    sed -E 's#x-access-token:[^@]+@#x-access-token:***@#g' "${ls_remote_error}" >&2
    rm -f "${ls_remote_error}"
    exit "${ls_remote_status}"
  fi
fi

bash ./.github/scripts/prepare-api-site.sh

git -C "${site_dir}" config user.name "github-actions[bot]"
git -C "${site_dir}" config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git -C "${site_dir}" add --all

if git -C "${site_dir}" diff --cached --quiet; then
  echo "No API docs changes to publish."
  exit 0
fi

git -C "${site_dir}" commit -m "docs(api): publish ${RELEASE_TAG}"
git -C "${site_dir}" push origin HEAD:gh-pages
