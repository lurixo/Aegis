# SPDX-License-Identifier: GPL-3.0-only

set(source "src/llama-model.cpp")
set(factory "static llama_model * llama_model_mapping(llm_arch arch, const llama_model_params & params) {\n")
set(guard "    if (arch != LLM_ARCH_QWEN3) {\n        throw std::runtime_error(std::string(\"unsupported model architecture: '\") + llm_arch_name(arch) + \"'\");\n    }\n")

file(READ "${source}" text)
string(FIND "${text}" "${factory}${guard}" guarded)
if(guarded EQUAL -1)
    string(FIND "${text}" "${factory}" found)
    if(found EQUAL -1)
        message(FATAL_ERROR "${source} does not define llama_model_mapping as expected")
    endif()
    string(REPLACE "${factory}" "${factory}${guard}" text "${text}")
    file(WRITE "${source}" "${text}")
endif()
