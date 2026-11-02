#!/usr/bin/env bats
# S3 through curl's --aws-sigv4 signer: path-style requests signed by libcurl, not an SDK.
# Clients without an AWS SDK use this shape, for example lean-cloud's blob store
# (https://github.com/palladin/lean-cloud/blob/main/runtime/LeanCloudRuntime/S3.lean).
# With FLOCI_AUTH_VALIDATE_SIGNATURES=true these also check that Floci verifies libcurl's
# canonical request, including keys with an encoded slash; a wrong secret then fails them.

setup() {
    load 'test_helper/common-setup'
    BUCKET="bats-curl-sigv4-$(unique_name)"
    URL="$FLOCI_ENDPOINT/$BUCKET"
}

teardown() {
    aws_cmd s3 rb "s3://$BUCKET" --force >/dev/null 2>&1 || true
}

# s3curl ARGS... -> prints the HTTP status code; `body` prints the response body
s3curl() {
    curl -sS -o "$BATS_TEST_TMPDIR/body" -w '%{http_code}' \
        --aws-sigv4 "aws:amz:$AWS_DEFAULT_REGION:s3" -u "$AWS_ACCESS_KEY_ID:$AWS_SECRET_ACCESS_KEY" "$@"
}

body() { cat "$BATS_TEST_TMPDIR/body"; }

@test "S3 curl-sigv4: create bucket, put, get, head, delete" {
    run s3curl -X PUT "$URL"
    assert_output "200"

    run s3curl -X PUT --data-binary 'hello' "$URL/dir%2Fkey"
    assert_output "200"

    run s3curl "$URL/dir%2Fkey"
    assert_output "200"
    [[ "$(body)" == "hello" ]]

    run curl -sS -I --aws-sigv4 "aws:amz:$AWS_DEFAULT_REGION:s3" \
        -u "$AWS_ACCESS_KEY_ID:$AWS_SECRET_ACCESS_KEY" "$URL/dir%2Fkey"
    assert_success
    assert_output --regexp $'[Cc]ontent-[Ll]ength: 5\r'

    run s3curl -I "$URL/missing"
    assert_output "404"

    run s3curl -X DELETE "$URL/dir%2Fkey"
    assert_output "204"
    run s3curl "$URL/dir%2Fkey"
    assert_output "404"
}

@test "S3 curl-sigv4: If-None-Match * refuses to overwrite" {
    s3curl -X PUT "$URL" >/dev/null

    run s3curl -X PUT -H 'If-None-Match: *' --data-binary 'first' "$URL/record"
    assert_output "200"

    run s3curl -X PUT -H 'If-None-Match: *' --data-binary 'second' "$URL/record"
    assert_output "412"

    s3curl "$URL/record" >/dev/null
    [[ "$(body)" == "first" ]]
}

@test "S3 curl-sigv4: concurrent If-None-Match * admits exactly one writer" {
    s3curl -X PUT "$URL" >/dev/null

    local i
    for i in $(seq 1 16); do
        curl -sS -o /dev/null -w "%{http_code} w$i\n" --aws-sigv4 "aws:amz:$AWS_DEFAULT_REGION:s3" \
            -u "$AWS_ACCESS_KEY_ID:$AWS_SECRET_ACCESS_KEY" \
            -X PUT -H 'If-None-Match: *' --data-binary "w$i" "$URL/race" &
    done > "$BATS_TEST_TMPDIR/race"
    wait

    [[ "$(grep -c '^200 ' "$BATS_TEST_TMPDIR/race")" == "1" ]]
    [[ "$(grep -c '^412 ' "$BATS_TEST_TMPDIR/race")" == "15" ]]
    winner=$(awk '$1 == 200 {print $2}' "$BATS_TEST_TMPDIR/race")
    s3curl "$URL/race" >/dev/null
    [[ "$(body)" == "$winner" ]]
}

@test "S3 curl-sigv4: ListObjectsV2 with encoding-type=url" {
    s3curl -X PUT "$URL" >/dev/null
    s3curl -X PUT --data-binary 'a' "$URL/runs%2Fone" >/dev/null
    s3curl -X PUT --data-binary 'b' "$URL/runs%2Fa%20b" >/dev/null

    run s3curl "$URL?list-type=2&encoding-type=url&max-keys=128&prefix=runs%2F"
    assert_output "200"
    [[ "$(body)" == *"<KeyCount>2</KeyCount>"* ]]
    [[ "$(body)" == *"<Key>runs%2Fone</Key>"* ]]
    [[ "$(body)" == *"<Key>runs%2Fa%20b</Key>"* ]]
    [[ "$(body)" == *"<EncodingType>url</EncodingType>"* ]]

    run s3curl "$URL?list-type=2&max-keys=0"
    assert_output "200"
    [[ "$(body)" == *"<KeyCount>0</KeyCount>"* ]]
    [[ "$(body)" != *"<Contents>"* ]]
}
