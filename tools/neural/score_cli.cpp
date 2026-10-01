// SPDX-License-Identifier: GPL-3.0-only
//
// Copyright (C) 2026 lurixo
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, version 3.
//
// This program is distributed in the hope that it will be useful, but WITHOUT ANY
// WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
// PARTICULAR PURPOSE. See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along with
// this program. If not, see <https://www.gnu.org/licenses/>.

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <memory>
#include <string>
#include <vector>

#include "neural_scorer.h"

namespace {

struct Row {
    std::string group;
    std::string key;
    std::string context;
    std::string candidate;
};

bool split(const std::string & line, Row * row) {
    size_t a = line.find('\t');
    if (a == std::string::npos) return false;
    size_t b = line.find('\t', a + 1);
    if (b == std::string::npos) return false;
    size_t c = line.find('\t', b + 1);
    if (c == std::string::npos) return false;
    row->group = line.substr(0, a);
    row->key = line.substr(a + 1, b - a - 1);
    row->context = line.substr(b + 1, c - b - 1);
    row->candidate = line.substr(c + 1);
    return true;
}

int usage() {
    std::fprintf(stderr, "usage: aegis_neural_score -m MODEL.gguf [-t THREADS] [-i IN.tsv] [-o OUT.tsv] [--embedded OFFSET]\n");
    return 2;
}

}  // namespace

int main(int argc, char ** argv) {
    std::string model;
    std::string in = "-";
    std::string out = "-";
    int threads = 4;
    long embedded = -1;
    for (int i = 1; i < argc; ++i) {
        const std::string arg = argv[i];
        if (i + 1 >= argc) return usage();
        if (arg == "-m") model = argv[++i];
        else if (arg == "-t") threads = std::atoi(argv[++i]);
        else if (arg == "-i") in = argv[++i];
        else if (arg == "-o") out = argv[++i];
        else if (arg == "--embedded") embedded = std::atol(argv[++i]);
        else return usage();
    }
    if (model.empty()) return usage();

    std::string error;
    std::unique_ptr<aegis::NeuralScorer> scorer;
    const auto loadStart = std::chrono::steady_clock::now();
    if (embedded >= 0) {
        FILE * file = std::fopen(model.c_str(), "rb");
        if (file == nullptr || std::fseek(file, embedded, SEEK_SET) != 0) {
            std::fprintf(stderr, "cannot open %s at %ld\n", model.c_str(), embedded);
            return 1;
        }
        scorer.reset(aegis::NeuralScorer::open(file, threads, &error));
    } else {
        scorer.reset(aegis::NeuralScorer::openPath(model.c_str(), threads, &error));
    }
    if (!scorer) {
        std::fprintf(stderr, "load failed: %s\n", error.c_str());
        return 1;
    }
    const double loadMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - loadStart).count();

    std::ifstream fileIn;
    std::istream * input = &std::cin;
    if (in != "-") {
        fileIn.open(in);
        if (!fileIn) {
            std::fprintf(stderr, "cannot read %s\n", in.c_str());
            return 1;
        }
        input = &fileIn;
    }
    FILE * output = out == "-" ? stdout : std::fopen(out.c_str(), "w");
    if (output == nullptr) {
        std::fprintf(stderr, "cannot write %s\n", out.c_str());
        return 1;
    }

    std::vector<Row> rows;
    std::string line;
    while (std::getline(*input, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line.empty()) continue;
        Row row;
        if (!split(line, &row)) {
            std::fprintf(stderr, "bad line: %s\n", line.c_str());
            return 1;
        }
        rows.push_back(std::move(row));
    }

    size_t groups = 0;
    double totalMs = 0.0;
    double maxMs = 0.0;
    size_t at = 0;
    std::vector<double> logprobs;
    std::vector<int> counts;
    while (at < rows.size()) {
        size_t end = at;
        while (end < rows.size() && rows[end].group == rows[at].group) ++end;
        std::vector<std::string> candidates;
        for (size_t i = at; i < end; ++i) candidates.push_back(rows[i].candidate);
        const auto start = std::chrono::steady_clock::now();
        const auto status = scorer->score(rows[at].context, candidates, &logprobs, &counts);
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
        if (status != aegis::NeuralScorer::Status::Ok) {
            std::fprintf(stderr, "scoring failed for group %s\n", rows[at].group.c_str());
            return 1;
        }
        for (size_t i = at; i < end; ++i) {
            std::fprintf(output, "%s\t%s\t%.6f\t%d\n", rows[i].key.c_str(), rows[i].candidate.c_str(),
                         logprobs[i - at], counts[i - at]);
        }
        ++groups;
        totalMs += ms;
        if (ms > maxMs) maxMs = ms;
        at = end;
    }
    if (output != stdout) std::fclose(output);
    std::fprintf(stderr, "load_ms=%.1f groups=%zu rows=%zu mean_group_ms=%.2f max_group_ms=%.2f threads=%d\n",
                 loadMs, groups, rows.size(), groups ? totalMs / groups : 0.0, maxMs, threads);
    return 0;
}
