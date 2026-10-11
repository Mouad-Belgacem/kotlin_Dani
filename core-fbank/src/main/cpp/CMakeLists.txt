cmake_minimum_required(VERSION 3.22.1)
project(core_fbank_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# Version epinglee explicitement (v1.22.3, dernier tag stable au moment ou ce
# module a ete ecrit - verifie sur https://github.com/csukuangfj/kaldi-native-fbank/tags,
# pas suppose). Statique : on ne veut qu'un seul .so final (celui de ce
# module), pas une bibliotheque partagee kaldi-native-fbank en plus a
# embarquer/charger separement.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(KALDI_NATIVE_FBANK_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(KALDI_NATIVE_FBANK_BUILD_PYTHON OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    kaldi_native_fbank
    GIT_REPOSITORY https://github.com/csukuangfj/kaldi-native-fbank.git
    GIT_TAG v1.22.3
)
FetchContent_MakeAvailable(kaldi_native_fbank)

add_library(core_fbank_jni SHARED
    fbank_jni.cpp
)

# kaldi-native-fbank-core n'exporte pas son repertoire racine comme include
# public utilisable depuis l'exterieur du sous-projet - ajoute explicitement
# (les headers sont inclus par le projet comme "kaldi-native-fbank/csrc/*.h",
# relatif a la racine du depot fetch).
target_include_directories(core_fbank_jni PRIVATE ${kaldi_native_fbank_SOURCE_DIR})

target_link_libraries(core_fbank_jni
    PRIVATE
    kaldi-native-fbank-core
    log
)
