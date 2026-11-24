#!/usr/bin/env bash
# Fails when code contains Chinese characters (design.md 12.4: code is English only).
#
#   scripts/check-no-chinese.sh
#
# Scans the files git knows about: tracked files plus new, not ignored files (so a file is checked
# before it is committed). Binary files are skipped. Exceptions: Markdown documents (*.md) and the
# Chinese language pack of the frontend (web/src/locales/zh-CN.*).
# Matches CJK unified ideographs (incl. extension A), CJK symbols and punctuation, and fullwidth forms.
# Prints "file:line: text" for every hit and exits with 1; exits with 0 when nothing is found.
set -euo pipefail

cd "$(dirname "$0")/.."

git ls-files -z --cached --others --exclude-standard | perl -0 -ne '
  BEGIN { $found = 0 }
  chomp;
  my $file = $_;
  next if $file =~ /\.md\z/;
  next if $file =~ m{\Aweb/src/locales/zh-CN\.[^/]+\z};
  next unless -f $file;   # tracked but deleted in the working tree
  next if -B $file;       # binary
  open(my $fh, "<:encoding(UTF-8)", $file) or die "cannot read $file: $!\n";
  local $/ = "\n";       # -0 reads the file list NUL-separated; read files line by line
  while (my $line = <$fh>) {
    if ($line =~ /[\x{3400}-\x{4DBF}\x{4E00}-\x{9FFF}\x{3000}-\x{303F}\x{FF00}-\x{FFEF}]/) {
      chomp $line;
      binmode(STDOUT, ":encoding(UTF-8)");
      print "$file:$.: $line\n";
      $found = 1;
    }
  }
  close $fh;
  END {
    if ($found) {
      print STDERR "Chinese characters found in code (design.md 12.4). Only *.md and web/src/locales/zh-CN.* may contain Chinese.\n";
      exit 1;
    }
  }
'
