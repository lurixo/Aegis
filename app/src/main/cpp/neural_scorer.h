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

#pragma once

#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

namespace aegis {

class NeuralScorer {
public:
    enum class Status { Ok, Aborted, Failed };

    static constexpr int kMaxCandidates = 16;
    static constexpr int kMaxContextTokens = 64;

    static NeuralScorer * open(FILE * file, int threads, std::string * error);
    static NeuralScorer * openPath(const char * path, int threads, std::string * error);

    ~NeuralScorer();

    Status score(const std::string & context,
                 const std::vector<std::string> & candidates,
                 std::vector<double> * logprobs,
                 std::vector<int> * tokenCounts);

    void cancel();

    struct Impl;

private:
    explicit NeuralScorer(Impl * impl);
    Impl * impl_;
};

}  // namespace aegis
