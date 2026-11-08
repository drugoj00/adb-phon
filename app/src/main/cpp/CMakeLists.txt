cmake_minimum_required(VERSION 3.18)
project(unrar_jni CXX)
# Только распаковка RAR (UnRAR, rarlab.com). Оптимизация по размеру, лишнее выбрасывается на этапе сборки.
set(U ${CMAKE_CURRENT_SOURCE_DIR}/unrar)
set(SRC rar strlist strfn pathfn smallfn global file filefn filcreat archive arcread unicode system crypt crc rawread encname
    resource match timefn rdwrfn consio options errhnd rarvm secpassword rijndael getbits sha1 sha256 blake2s hash extinfo extract volume
    list find unpack headers threadpool rs16 cmddata ui largepage filestr scantree dll qopen)
set(FILES "")
foreach(s ${SRC})
    list(APPEND FILES ${U}/${s}.cpp)
endforeach()
add_library(unrar_jni SHARED unrar_jni.cpp ${FILES})
target_compile_definitions(unrar_jni PRIVATE _UNIX RARDLL SILENT _FILE_OFFSET_BITS=64 _LARGEFILE_SOURCE)
target_compile_options(unrar_jni PRIVATE -Os -fvisibility=hidden -ffunction-sections -fdata-sections -w)
target_link_options(unrar_jni PRIVATE -Wl,--gc-sections -Wl,--strip-all -Wl,-z,max-page-size=16384)
target_link_libraries(unrar_jni log)
