package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;

/**
 * Centralizes Vulkan/Beryl cmdgen diagnostic shader and env-var selection.
 *
 * <p>This class intentionally keeps the existing probe/env-var names and defaults so
 * {@link VulkanBerylSectionDrawPipeline} can focus on pipeline creation, dispatch,
 * draw submission, and readback plumbing.</p>
 */
final class VulkanBerylCmdgenDiagnostics {
    static final String CMDGEN_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen.comp";
    static final String CMDGEN_SHADER_NAME = "vulkanberyl/section/cmdgen";
    static final String CMDGEN_NO_DRAWCOUNT_WRITE_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_no_drawcount_write.comp";
    static final String CMDGEN_NO_DRAWCOUNT_WRITE_SHADER_NAME = "vulkanberyl/section/cmdgen_no_drawcount_write";
    static final String CMDGEN_NOOP_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_dispatch_noop.comp";
    static final String CMDGEN_NOOP_SHADER_NAME = "vulkanberyl/section/cmdgen_dispatch_noop";
    static final String CMDGEN_MINIMAL_SSBO_READ_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_minimal_ssbo_read.comp";
    static final String CMDGEN_MINIMAL_SSBO_READ_SHADER_NAME = "vulkanberyl/section/cmdgen_minimal_ssbo_read";
    static final String CMDGEN_MINIMAL_CONFIG_READ_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_minimal_config_read.comp";
    static final String CMDGEN_MINIMAL_CONFIG_READ_SHADER_NAME = "vulkanberyl/section/cmdgen_minimal_config_read";
    static final String CMDGEN_MINIMAL_CONFIG_BINDING0_READ_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_minimal_config_binding0_read.comp";
    static final String CMDGEN_MINIMAL_CONFIG_BINDING0_READ_SHADER_NAME = "vulkanberyl/section/cmdgen_minimal_config_binding0_read";
    static final String CMDGEN_HARDCODED_BINDING0_READ_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_hardcoded_binding0_read.comp";
    static final String CMDGEN_HARDCODED_BINDING0_READ_SHADER_NAME = "vulkanberyl/section/cmdgen_hardcoded_binding0_read";
    static final String CMDGEN_FULL_LAYOUT_NOOP_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_full_layout_noop.comp";
    static final String CMDGEN_FULL_LAYOUT_NOOP_SHADER_NAME = "vulkanberyl/section/cmdgen_full_layout_noop";
    static final String CMDGEN_FULL_LAYOUT_HARDCODED_BINDING0_READ_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_full_layout_hardcoded_binding0_read.comp";
    static final String CMDGEN_FULL_LAYOUT_HARDCODED_BINDING0_READ_SHADER_NAME = "vulkanberyl/section/cmdgen_full_layout_hardcoded_binding0_read";
    static final String CMDGEN_FULL_LAYOUT_CONFIG_BINDING0_READ_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_full_layout_config_binding0_read.comp";
    static final String CMDGEN_FULL_LAYOUT_CONFIG_BINDING0_READ_SHADER_NAME = "vulkanberyl/section/cmdgen_full_layout_config_binding0_read";
    static final String CMDGEN_STANDALONE_BINDING0_CONFIG_READ_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_standalone_binding0_config_read.comp";
    static final String CMDGEN_STANDALONE_BINDING0_CONFIG_READ_SHADER_NAME = "vulkanberyl/section/cmdgen_standalone_binding0_config_read";
    static final String CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_SECTION_IMPORT_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_standalone_binding0_config_with_section_import.comp";
    static final String CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_SECTION_IMPORT_SHADER_NAME = "vulkanberyl/section/cmdgen_standalone_binding0_config_with_section_import";
    static final String CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_CMDGEN_DECLS_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_standalone_binding0_config_with_cmdgen_decls.comp";
    static final String CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_CMDGEN_DECLS_SHADER_NAME = "vulkanberyl/section/cmdgen_standalone_binding0_config_with_cmdgen_decls";
    static final String CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_SIMPLE_FLAG_BRANCH_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_standalone_binding0_config_simple_flag_branch.comp";
    static final String CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_SIMPLE_FLAG_BRANCH_SHADER_NAME = "vulkanberyl/section/cmdgen_standalone_binding0_config_simple_flag_branch";
    static final String CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_READONLY_BRANCH_LADDER_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_standalone_binding0_config_readonly_branch_ladder.comp";
    static final String CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_READONLY_BRANCH_LADDER_SHADER_NAME = "vulkanberyl/section/cmdgen_standalone_binding0_config_readonly_branch_ladder";
    static final String CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_FULL_BRANCH_LADDER_NO_HELPERS_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_standalone_binding0_config_full_branch_ladder_no_helpers.comp";
    static final String CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_FULL_BRANCH_LADDER_NO_HELPERS_SHADER_NAME = "vulkanberyl/section/cmdgen_standalone_binding0_config_full_branch_ladder_no_helpers";
    static final String CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_HELPER_DECLS_UNUSED_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_standalone_binding0_config_with_helper_decls_unused.comp";
    static final String CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_HELPER_DECLS_UNUSED_SHADER_NAME = "vulkanberyl/section/cmdgen_standalone_binding0_config_with_helper_decls_unused";
    static final String CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_HELPER_READONLY_USE_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_standalone_binding0_config_with_helper_readonly_use.comp";
    static final String CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_HELPER_READONLY_USE_SHADER_NAME = "vulkanberyl/section/cmdgen_standalone_binding0_config_with_helper_readonly_use";
    static final String CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_FINAL_BLOCK_NO_OUTPUT_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_standalone_binding0_config_final_block_no_output.comp";
    static final String CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_FINAL_BLOCK_NO_OUTPUT_SHADER_NAME = "vulkanberyl/section/cmdgen_standalone_binding0_config_final_block_no_output";
    static final String CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_FINAL_COMMAND_WRITE_NO_DRAWCOUNT_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_standalone_binding0_config_final_command_write_no_drawcount.comp";
    static final String CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_FINAL_COMMAND_WRITE_NO_DRAWCOUNT_SHADER_NAME = "vulkanberyl/section/cmdgen_standalone_binding0_config_final_command_write_no_drawcount";
    static final String CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_FINAL_DRAWCOUNT_WRITE_ONLY_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_standalone_binding0_config_final_drawcount_write_only.comp";
    static final String CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_FINAL_DRAWCOUNT_WRITE_ONLY_SHADER_NAME = "vulkanberyl/section/cmdgen_standalone_binding0_config_final_drawcount_write_only";
    static final String CMDGEN_FINAL_COMMAND_WRITE_CONSTANT_AFTER_METADATA_READ_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_final_command_write_constant_after_metadata_read.comp";
    static final String CMDGEN_FINAL_COMMAND_WRITE_CONSTANT_AFTER_METADATA_READ_SHADER_NAME = "vulkanberyl/section/cmdgen_final_command_write_constant_after_metadata_read";
    static final String CMDGEN_FINAL_COMMAND_WRITE_COMPUTED_VERTEX_COUNT_ONLY_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_final_command_write_computed_vertex_count_only.comp";
    static final String CMDGEN_FINAL_COMMAND_WRITE_COMPUTED_VERTEX_COUNT_ONLY_SHADER_NAME = "vulkanberyl/section/cmdgen_final_command_write_computed_vertex_count_only";
    static final String CMDGEN_FINAL_COMMAND_WRITE_COMPUTED_FIRST_VERTEX_ONLY_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_final_command_write_computed_first_vertex_only.comp";
    static final String CMDGEN_FINAL_COMMAND_WRITE_COMPUTED_FIRST_VERTEX_ONLY_SHADER_NAME = "vulkanberyl/section/cmdgen_final_command_write_computed_first_vertex_only";
    static final String CMDGEN_FINAL_COMMAND_WRITE_CLAMPED_COMPUTED_COMMAND_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_final_command_write_clamped_computed_command.comp";
    static final String CMDGEN_FINAL_COMMAND_WRITE_CLAMPED_COMPUTED_COMMAND_SHADER_NAME = "vulkanberyl/section/cmdgen_final_command_write_clamped_computed_command";
    static final String CMDGEN_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_METADATA_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_command_write_after_renderlist_read_no_metadata.comp";
    static final String CMDGEN_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_METADATA_SHADER_NAME = "vulkanberyl/section/cmdgen_command_write_after_renderlist_read_no_metadata";
    static final String CMDGEN_COMMAND_WRITE_AFTER_METADATA0_READ_NO_RENDERLIST_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_command_write_after_metadata0_read_no_renderlist.comp";
    static final String CMDGEN_COMMAND_WRITE_AFTER_METADATA0_READ_NO_RENDERLIST_SHADER_NAME = "vulkanberyl/section/cmdgen_command_write_after_metadata0_read_no_renderlist";
    static final String CMDGEN_COMMAND_WRITE_BEFORE_METADATA_READ_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_command_write_before_metadata_read.comp";
    static final String CMDGEN_COMMAND_WRITE_BEFORE_METADATA_READ_SHADER_NAME = "vulkanberyl/section/cmdgen_command_write_before_metadata_read";
    static final String CMDGEN_COMMAND_WRITE_AFTER_VISIBLECOUNT_READ_ONLY_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_command_write_after_visiblecount_read_only.comp";
    static final String CMDGEN_COMMAND_WRITE_AFTER_VISIBLECOUNT_READ_ONLY_SHADER_NAME = "vulkanberyl/section/cmdgen_command_write_after_visiblecount_read_only";
    static final String CMDGEN_COMMAND_WRITE_AFTER_INDIRECTLOOKUP0_READ_ONLY_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_command_write_after_indirectlookup0_read_only.comp";
    static final String CMDGEN_COMMAND_WRITE_AFTER_INDIRECTLOOKUP0_READ_ONLY_SHADER_NAME = "vulkanberyl/section/cmdgen_command_write_after_indirectlookup0_read_only";
    static final String CMDGEN_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_BRANCH_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_command_write_after_renderlist_read_no_branch.comp";
    static final String CMDGEN_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_BRANCH_SHADER_NAME = "vulkanberyl/section/cmdgen_command_write_after_renderlist_read_no_branch";
    static final String CMDGEN_COMMAND_WRITE_AFTER_VISIBLECOUNT_BRANCH_ONLY_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_command_write_after_visiblecount_branch_only.comp";
    static final String CMDGEN_COMMAND_WRITE_AFTER_VISIBLECOUNT_BRANCH_ONLY_SHADER_NAME = "vulkanberyl/section/cmdgen_command_write_after_visiblecount_branch_only";
    static final String CMDGEN_STANDALONE_DRAWCOUNT_LITERAL_ZERO_WRITE_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_standalone_drawcount_literal_zero_write.comp";
    static final String CMDGEN_STANDALONE_DRAWCOUNT_LITERAL_ZERO_WRITE_SHADER_NAME = "vulkanberyl/section/cmdgen_standalone_drawcount_literal_zero_write";
    static final String CMDGEN_STANDALONE_DRAWCOUNT_LITERAL_ONE_WRITE_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_standalone_drawcount_literal_one_write.comp";
    static final String CMDGEN_STANDALONE_DRAWCOUNT_LITERAL_ONE_WRITE_SHADER_NAME = "vulkanberyl/section/cmdgen_standalone_drawcount_literal_one_write";
    static final String CMDGEN_STANDALONE_DRAWCOUNT_NO_CONFIG_LITERAL_ZERO_WRITE_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_standalone_drawcount_no_config_literal_zero_write.comp";
    static final String CMDGEN_STANDALONE_DRAWCOUNT_NO_CONFIG_LITERAL_ZERO_WRITE_SHADER_NAME = "vulkanberyl/section/cmdgen_standalone_drawcount_no_config_literal_zero_write";
    static final String CMDGEN_STANDALONE_DRAWCOUNT_DECLARED_NO_WRITE_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_standalone_drawcount_declared_no_write.comp";
    static final String CMDGEN_STANDALONE_DRAWCOUNT_DECLARED_NO_WRITE_SHADER_NAME = "vulkanberyl/section/cmdgen_standalone_drawcount_declared_no_write";
    static final String CMDGEN_NO_IMPORT_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_no_import.comp";
    static final String CMDGEN_NO_IMPORT_SHADER_NAME = "vulkanberyl/section/cmdgen_no_import";
    static final String CMDGEN_NO_IMPORT_READ_METADATA0_ONLY_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_no_import_read_metadata0_only.comp";
    static final String CMDGEN_NO_IMPORT_READ_METADATA0_ONLY_SHADER_NAME = "vulkanberyl/section/cmdgen_no_import_read_metadata0_only";
    static final String CMDGEN_NO_IMPORT_RAW_METADATA_UVEC4_BINDING1_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_no_import_raw_metadata_uvec4_binding1.comp";
    static final String CMDGEN_NO_IMPORT_RAW_METADATA_UVEC4_BINDING1_SHADER_NAME = "vulkanberyl/section/cmdgen_no_import_raw_metadata_uvec4_binding1";
    static final String CMDGEN_FULL_LAYOUT_BINDING1_UINT_READ_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_full_layout_binding1_uint_read.comp";
    static final String CMDGEN_FULL_LAYOUT_BINDING1_UINT_READ_SHADER_NAME = "vulkanberyl/section/cmdgen_full_layout_binding1_uint_read";
    static final String CMDGEN_FULL_LAYOUT_BINDING1_UINT_READ_CONST_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_full_layout_binding1_uint_read_const.comp";
    static final String CMDGEN_FULL_LAYOUT_BINDING1_UINT_READ_CONST_SHADER_NAME = "vulkanberyl/section/cmdgen_full_layout_binding1_uint_read_const";
    static final String CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_NO_CONFIG_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_full_layout_binding1_tiny_uint_read_no_config.comp";
    static final String CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_NO_CONFIG_SHADER_NAME = "vulkanberyl/section/cmdgen_full_layout_binding1_tiny_uint_read_no_config";
    static final String CMDGEN_FULL_LAYOUT_BINDING1_AND_BINDING2_UINT_READ_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_full_layout_binding1_and_binding2_uint_read.comp";
    static final String CMDGEN_FULL_LAYOUT_BINDING1_AND_BINDING2_UINT_READ_SHADER_NAME = "vulkanberyl/section/cmdgen_full_layout_binding1_and_binding2_uint_read";
    static final String CMDGEN_FULL_LAYOUT_BINDING2_PROBE_BUFFER_UINT_READ_NO_CONFIG_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_full_layout_binding2_probe_buffer_uint_read_no_config.comp";
    static final String CMDGEN_FULL_LAYOUT_BINDING2_PROBE_BUFFER_UINT_READ_NO_CONFIG_SHADER_NAME = "vulkanberyl/section/cmdgen_full_layout_binding2_probe_buffer_uint_read_no_config";
    static final String CMDGEN_FULL_LAYOUT_BINDING2_PROBE_BUFFER_UINT_READ_CONST_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_full_layout_binding2_probe_buffer_uint_read_const.comp";
    static final String CMDGEN_FULL_LAYOUT_BINDING2_PROBE_BUFFER_UINT_READ_CONST_SHADER_NAME = "vulkanberyl/section/cmdgen_full_layout_binding2_probe_buffer_uint_read_const";
    static final String CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_TINY_UINT_READ_NO_CONFIG_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_full_layout_binding1_tiny_and_binding2_tiny_uint_read_no_config.comp";
    static final String CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_TINY_UINT_READ_NO_CONFIG_SHADER_NAME = "vulkanberyl/section/cmdgen_full_layout_binding1_tiny_and_binding2_tiny_uint_read_no_config";
    static final String CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_PROBE_BUFFER_UINT_READ_NO_CONFIG_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_full_layout_binding1_tiny_and_binding2_probe_buffer_uint_read_no_config.comp";
    static final String CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_PROBE_BUFFER_UINT_READ_NO_CONFIG_SHADER_NAME = "vulkanberyl/section/cmdgen_full_layout_binding1_tiny_and_binding2_probe_buffer_uint_read_no_config";
    static final String CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_PROBE_BUFFER_UINT_READ_CONST_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_full_layout_binding1_tiny_and_binding2_probe_buffer_uint_read_const.comp";
    static final String CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_PROBE_BUFFER_UINT_READ_CONST_SHADER_NAME = "vulkanberyl/section/cmdgen_full_layout_binding1_tiny_and_binding2_probe_buffer_uint_read_const";
    static final String CMDGEN_SINGLE_BINDING1_UINT_READ_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_single_binding1_uint_read.comp";
    static final String CMDGEN_SINGLE_BINDING1_UINT_READ_SHADER_NAME = "vulkanberyl/section/cmdgen_single_binding1_uint_read";
    static final String CMDGEN_BINDING0_UINT_READ_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_binding0_uint_read.comp";
    static final String CMDGEN_BINDING0_UINT_READ_SHADER_NAME = "vulkanberyl/section/cmdgen_binding0_uint_read";
    static final String CMDGEN_FULL_LAYOUT_BINDING2_UINT_READ_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_full_layout_binding2_uint_read.comp";
    static final String CMDGEN_FULL_LAYOUT_BINDING2_UINT_READ_SHADER_NAME = "vulkanberyl/section/cmdgen_full_layout_binding2_uint_read";
    static final String CMDGEN_RAW_METADATA_UVEC4_BINDING0_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_raw_metadata_uvec4_binding0.comp";
    static final String CMDGEN_RAW_METADATA_UVEC4_BINDING0_SHADER_NAME = "vulkanberyl/section/cmdgen_raw_metadata_uvec4_binding0";
    static final String CMDGEN_NO_IMPORT_COMPUTE_QUAD_COUNTS_ONLY_NO_WRITE_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_no_import_compute_quad_counts_only_no_write.comp";
    static final String CMDGEN_NO_IMPORT_COMPUTE_QUAD_COUNTS_ONLY_NO_WRITE_SHADER_NAME = "vulkanberyl/section/cmdgen_no_import_compute_quad_counts_only_no_write";
    static final String CMDGEN_READ_RENDERLIST_METADATA_NO_WRITE_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_read_renderlist_metadata_no_write.comp";
    static final String CMDGEN_READ_RENDERLIST_METADATA_NO_WRITE_SHADER_NAME = "vulkanberyl/section/cmdgen_read_renderlist_metadata_no_write";
    static final String CMDGEN_NO_IMPORT_WRITE_COMMAND0_ONLY_NO_ATOMIC_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_no_import_write_command0_only_no_atomic.comp";
    static final String CMDGEN_NO_IMPORT_WRITE_COMMAND0_ONLY_NO_ATOMIC_SHADER_NAME = "vulkanberyl/section/cmdgen_no_import_write_command0_only_no_atomic";
    static final String CMDGEN_NO_IMPORT_ATOMIC_DRAWCOUNT_ONLY_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_no_import_atomic_drawcount_only.comp";
    static final String CMDGEN_NO_IMPORT_ATOMIC_DRAWCOUNT_ONLY_SHADER_NAME = "vulkanberyl/section/cmdgen_no_import_atomic_drawcount_only";
    static final String CMDGEN_NO_IMPORT_SINGLE_INVOCATION_REAL_COMMAND_NO_ATOMIC_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_no_import_single_invocation_real_command_no_atomic.comp";
    static final String CMDGEN_NO_IMPORT_SINGLE_INVOCATION_REAL_COMMAND_NO_ATOMIC_SHADER_NAME = "vulkanberyl/section/cmdgen_no_import_single_invocation_real_command_no_atomic";
    static final String CMDGEN_DENSE_LAYOUT_NOOP_SHADER_RESOURCE = "voxy:shaders/vulkanberyl/section/cmdgen_dense_layout_noop.comp";
    static final String CMDGEN_DENSE_LAYOUT_NOOP_SHADER_NAME = "vulkanberyl/section/cmdgen_dense_layout_noop";
    static final String CMDGEN_SHADER_CONFIG = "/assets/voxy/shaders/vulkanberyl/section/cmdgen.json";

    static final int GEOMETRY_BINDING = 4;
    static final int METADATA_BINDING = 5;
    static final int RENDER_LIST_BINDING = 6;
    static final int SCENE_UNIFORM_BINDING = 0;
    static final int CMDGEN_RENDER_LIST_BINDING = 0;
    static final int CMDGEN_METADATA_BINDING = 1;
    static final int CMDGEN_UNUSED_BINDING2_BINDING = 2;
    static final int CMDGEN_BINDING2_PROBE_BINDING = 2;
    static final int CMDGEN_DRAW_COMMAND_BINDING = 3;
    static final int CMDGEN_DRAW_COUNT_BINDING = 4;
    static final int CMDGEN_CONFIG_BINDING = 5;
    static final int DRAW_COMMAND_STRIDE_BYTES = 16;
    static final int CMDGEN_CONFIG_SIZE_BYTES = 24;
    static final int CMDGEN_UNUSED_BINDING2_SIZE_BYTES = 32;
    static final int CMDGEN_DIAGNOSTIC_DRAWCOUNT_CAPACITY_BYTES = 256;
    static final int CMDGEN_BINDING2_PROBE_SIZE_BYTES = VulkanBerylSectionGeometryData.SECTION_METADATA_SIZE;
    static final int CMDGEN_CONFIG_USAGE_FLAGS = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    static final int CMDGEN_FLAG_NOOP_SMOKE = 1;
    static final int CMDGEN_FLAG_READ_RENDERLIST_ONLY = 1 << 1;
    static final int CMDGEN_FLAG_READ_METADATA_ONLY = 1 << 2;
    static final int CMDGEN_FLAG_WRITE_DRAWS_ONLY = 1 << 3;
    static final int CMDGEN_FLAG_READ_RENDERLIST_HEADER_ONLY = 1 << 4;
    static final int CMDGEN_FLAG_READ_RENDERLIST_COUNT_ONLY = 1 << 5;
    static final int CMDGEN_FLAG_READ_RENDERLIST_ENTRY0_SECTION_ID_ONLY = 1 << 6;
    static final int CMDGEN_FLAG_READ_RENDERLIST_ENTRY0_QUAD_START_ONLY = 1 << 7;
    static final int CMDGEN_FLAG_READ_RENDERLIST_ENTRY0_QUAD_COUNT_ONLY = 1 << 8;
    static final int CMDGEN_FLAG_READ_BINDING0_ONLY = 1 << 9;
    static final int CMDGEN_FLAG_READ_BINDING1_ONLY = 1 << 10;
    static final int CMDGEN_FLAG_READ_BINDING0_ONLY_NO_OUTPUT_WRITE = 1 << 11;
    static final int CMDGEN_FLAG_READ_CONFIG_ONLY = 1 << 12;
    static final int CMDGEN_FLAG_READ_RENDERLIST_METADATA_NO_WRITE = 1 << 13;
    static final int DRAW_COMMAND_DEBUG_SAMPLE_LIMIT = 16;
    static final boolean DEBUG_COLOUR_MODE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_DEBUG_COLOUR", "false"));
    static final boolean ENABLE_CMDGEN_DISPATCH = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_ENABLE_CMDGEN_DISPATCH", "false"));
    static final boolean CMDGEN_CREATE_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_CREATE_ONLY", "false"));
    static final boolean CMDGEN_DISPATCH_NOOP = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_DISPATCH_NOOP", "false"));
    static final boolean CMDGEN_DESCRIPTOR_NOOP_BIND_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_DESCRIPTOR_NOOP_BIND_PROBE", "false"));
    static final boolean CMDGEN_UPLOAD_CONFIG_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_UPLOAD_CONFIG_ONLY", "false"));
    static final boolean CMDGEN_CLEAR_OUTPUTS_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_CLEAR_OUTPUTS_ONLY", "false"));
    static final boolean CMDGEN_BIND_FULL_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_BIND_FULL_ONLY", "false"));
    static final boolean CMDGEN_DISABLE_BIND_PIPELINE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_DISABLE_BIND_PIPELINE", "false"));
    static final boolean CMDGEN_DISABLE_BIND_DESCRIPTORS = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_DISABLE_BIND_DESCRIPTORS", "false"));
    static final boolean CMDGEN_DISABLE_DISPATCH_CALL = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_DISABLE_DISPATCH_CALL", "false"));
    static final boolean CMDGEN_DISABLE_POST_DISPATCH_BARRIER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_DISABLE_POST_DISPATCH_BARRIER", "false"));
    static final boolean CMDGEN_DISPATCH_NOOP_SAME_LAYOUT = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_DISPATCH_NOOP_SAME_LAYOUT", "false"));
    static final boolean CMDGEN_SHADER_READ_RENDERLIST_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_ONLY", "false"));
    static final boolean CMDGEN_SHADER_READ_RENDERLIST_HEADER_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_HEADER_ONLY", "false"));
    static final boolean CMDGEN_SHADER_READ_RENDERLIST_COUNT_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_COUNT_ONLY", "false"));
    static final boolean CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_SECTION_ID_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_SECTION_ID_ONLY", "false"));
    static final boolean CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_QUAD_START_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_QUAD_START_ONLY", "false"));
    static final boolean CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_QUAD_COUNT_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_QUAD_COUNT_ONLY", "false"));
    static final boolean CMDGEN_SHADER_READ_METADATA_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_METADATA_ONLY", "false"));
    static final boolean CMDGEN_SHADER_WRITE_DRAWS_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_SHADER_WRITE_DRAWS_ONLY", "false"));
    static final boolean CMDGEN_SHADER_READ_BINDING0_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_BINDING0_ONLY", "false"));
    static final boolean CMDGEN_SHADER_READ_BINDING0_ONLY_NO_OUTPUT_WRITE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_BINDING0_ONLY_NO_OUTPUT_WRITE", "false"));
    static final boolean CMDGEN_SHADER_READ_BINDING1_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_BINDING1_ONLY", "false"));
    static final boolean CMDGEN_SHADER_READ_CONFIG_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_CONFIG_ONLY", "false"));
    static final boolean CMDGEN_SHADER_READ_RENDERLIST_METADATA_NO_WRITE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_METADATA_NO_WRITE", "false"));
    static final boolean CMDGEN_RENDERLIST_ALT_BUFFER_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_RENDERLIST_ALT_BUFFER_PROBE", "false"));
    static final boolean CMDGEN_MINIMAL_TINY_SSBO_READ_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_MINIMAL_TINY_SSBO_READ_PROBE", "false"));
    static final boolean CMDGEN_MINIMAL_RENDERLIST_MANUALUBO_READ_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_MINIMAL_RENDERLIST_MANUALUBO_READ_PROBE", "false"));
    static final boolean CMDGEN_MINIMAL_CONFIG_READ_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_MINIMAL_CONFIG_READ_PROBE", "false"));
    static final boolean CMDGEN_MINIMAL_CONFIG_BINDING0_READ_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_MINIMAL_CONFIG_BINDING0_READ_PROBE", "false"));
    static final boolean CMDGEN_HARDCODED_READ_BINDING0_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_HARDCODED_READ_BINDING0_ONLY", "false"));
    static final boolean CMDGEN_FULL_LAYOUT_NOOP_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_NOOP_PROBE", "false"));
    static final boolean CMDGEN_FULL_LAYOUT_HARDCODED_BINDING0_READ_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_HARDCODED_BINDING0_READ_PROBE", "false"));
    static final boolean CMDGEN_FULL_LAYOUT_CONFIG_BINDING0_READ_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_CONFIG_BINDING0_READ_PROBE", "false"));
    static final boolean CMDGEN_USE_STANDALONE_BINDING0_CONFIG_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_SHADER", "false"));
    static final boolean CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_SECTION_IMPORT_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_SECTION_IMPORT_SHADER", "false"));
    static final boolean CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_CMDGEN_DECLS_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_CMDGEN_DECLS_SHADER", "false"));
    static final boolean CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_SIMPLE_FLAG_BRANCH_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_SIMPLE_FLAG_BRANCH_SHADER", "false"));
    static final boolean CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_READONLY_BRANCH_LADDER_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_READONLY_BRANCH_LADDER_SHADER", "false"));
    static final boolean CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FULL_BRANCH_LADDER_NO_HELPERS_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FULL_BRANCH_LADDER_NO_HELPERS_SHADER", "false"));
    static final boolean CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_HELPER_DECLS_UNUSED_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_HELPER_DECLS_UNUSED_SHADER", "false"));
    static final boolean CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_HELPER_READONLY_USE_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_HELPER_READONLY_USE_SHADER", "false"));
    static final boolean CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_BLOCK_NO_OUTPUT_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_BLOCK_NO_OUTPUT_SHADER", "false"));
    static final boolean CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_COMMAND_WRITE_NO_DRAWCOUNT_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_COMMAND_WRITE_NO_DRAWCOUNT_SHADER", "false"));
    static final boolean CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_DRAWCOUNT_WRITE_ONLY_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_DRAWCOUNT_WRITE_ONLY_SHADER", "false"));
    static final boolean CMDGEN_USE_FINAL_COMMAND_WRITE_CONSTANT_AFTER_METADATA_READ_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_FINAL_COMMAND_WRITE_CONSTANT_AFTER_METADATA_READ_SHADER", "false"));
    static final boolean CMDGEN_USE_FINAL_COMMAND_WRITE_COMPUTED_VERTEX_COUNT_ONLY_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_FINAL_COMMAND_WRITE_COMPUTED_VERTEX_COUNT_ONLY_SHADER", "false"));
    static final boolean CMDGEN_USE_FINAL_COMMAND_WRITE_COMPUTED_FIRST_VERTEX_ONLY_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_FINAL_COMMAND_WRITE_COMPUTED_FIRST_VERTEX_ONLY_SHADER", "false"));
    static final boolean CMDGEN_USE_FINAL_COMMAND_WRITE_CLAMPED_COMPUTED_COMMAND_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_FINAL_COMMAND_WRITE_CLAMPED_COMPUTED_COMMAND_SHADER", "false"));
    static final boolean CMDGEN_USE_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_METADATA_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_METADATA_SHADER", "false"));
    static final boolean CMDGEN_USE_COMMAND_WRITE_AFTER_METADATA0_READ_NO_RENDERLIST_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_AFTER_METADATA0_READ_NO_RENDERLIST_SHADER", "false"));
    static final boolean CMDGEN_USE_COMMAND_WRITE_BEFORE_METADATA_READ_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_BEFORE_METADATA_READ_SHADER", "false"));
    static final boolean CMDGEN_USE_COMMAND_WRITE_AFTER_VISIBLECOUNT_READ_ONLY_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_AFTER_VISIBLECOUNT_READ_ONLY_SHADER", "false"));
    static final boolean CMDGEN_USE_COMMAND_WRITE_AFTER_INDIRECTLOOKUP0_READ_ONLY_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_AFTER_INDIRECTLOOKUP0_READ_ONLY_SHADER", "false"));
    static final boolean CMDGEN_USE_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_BRANCH_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_BRANCH_SHADER", "false"));
    static final boolean CMDGEN_USE_COMMAND_WRITE_AFTER_VISIBLECOUNT_BRANCH_ONLY_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_AFTER_VISIBLECOUNT_BRANCH_ONLY_SHADER", "false"));
    static final boolean CMDGEN_USE_STANDALONE_DRAWCOUNT_LITERAL_ZERO_WRITE_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_DRAWCOUNT_LITERAL_ZERO_WRITE_SHADER", "false"));
    static final boolean CMDGEN_USE_STANDALONE_DRAWCOUNT_LITERAL_ONE_WRITE_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_DRAWCOUNT_LITERAL_ONE_WRITE_SHADER", "false"));
    static final boolean CMDGEN_USE_STANDALONE_DRAWCOUNT_NO_CONFIG_LITERAL_ZERO_WRITE_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_DRAWCOUNT_NO_CONFIG_LITERAL_ZERO_WRITE_SHADER", "false"));
    static final boolean CMDGEN_USE_STANDALONE_DRAWCOUNT_DECLARED_NO_WRITE_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_DRAWCOUNT_DECLARED_NO_WRITE_SHADER", "false"));
    static final boolean CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER", "false"));
    static final boolean CMDGEN_BIND_DRAWCOUNT_TO_SCRATCH_BUFFER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_BIND_DRAWCOUNT_TO_SCRATCH_BUFFER", "false"));
    static final boolean CMDGEN_USE_LARGE_DRAWCOUNT_BUFFER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_LARGE_DRAWCOUNT_BUFFER", "false"));
    static final boolean CMDGEN_DRAWCOUNT_DESCRIPTOR_RANGE_FULL_BUFFER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_DRAWCOUNT_DESCRIPTOR_RANGE_FULL_BUFFER", "false"));
    static final boolean CMDGEN_USE_DRAWCOUNT_BUFFER_WITH_INDIRECT_USAGE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_DRAWCOUNT_BUFFER_WITH_INDIRECT_USAGE", "false"));
    static final boolean CMDGEN_USE_SCRATCH_ALLOCATION_FOR_REAL_DRAWCOUNT = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_SCRATCH_ALLOCATION_FOR_REAL_DRAWCOUNT", "false"));
    static final boolean CMDGEN_USE_PASSING_SCRATCH_BINDING_AS_REAL_DRAWCOUNT_DESCRIPTOR = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_PASSING_SCRATCH_BINDING_AS_REAL_DRAWCOUNT_DESCRIPTOR", "false"));
    static final boolean CMDGEN_SKIP_DRAWCOUNT_CLEAR_BEFORE_DISPATCH = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_SKIP_DRAWCOUNT_CLEAR_BEFORE_DISPATCH", "false"));
    static final boolean CMDGEN_DISABLE_ANY_DRAWCOUNT_CONSUMER_PATH = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_DISABLE_ANY_DRAWCOUNT_CONSUMER_PATH", "false"));
    static final boolean CMDGEN_DUMP_SHADER_DIAGNOSTICS = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_DUMP_SHADER_DIAGNOSTICS", "false"));
    static final boolean CMDGEN_NO_IMPORT_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_NO_IMPORT_PROBE", "false"));
    static final boolean CMDGEN_NO_IMPORT_READ_METADATA0_ONLY_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_NO_IMPORT_READ_METADATA0_ONLY_PROBE", "false"));
    static final boolean CMDGEN_NO_IMPORT_RAW_METADATA_UVEC4_BINDING1_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_NO_IMPORT_RAW_METADATA_UVEC4_BINDING1_PROBE", "false"));
    static final boolean CMDGEN_NO_IMPORT_RAW_METADATA_UVEC4_BINDING1_TINY_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_NO_IMPORT_RAW_METADATA_UVEC4_BINDING1_TINY_PROBE", "false"));
    static final boolean CMDGEN_FULL_LAYOUT_BINDING1_NO_READ_TINY_BIND_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_BINDING1_NO_READ_TINY_BIND_PROBE", "false"));
    static final boolean CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_PROBE", "false"));
    static final boolean CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_CONST_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_CONST_PROBE", "false"));
    static final boolean CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_NO_CONFIG_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_NO_CONFIG_PROBE", "false"));
    static final boolean CMDGEN_FULL_LAYOUT_BINDING1_AND_BINDING2_UINT_READ_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_BINDING1_AND_BINDING2_UINT_READ_PROBE", "false"));
    static final boolean CMDGEN_FULL_LAYOUT_BINDING2_PROBE_BUFFER_UINT_READ_NO_CONFIG_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_BINDING2_PROBE_BUFFER_UINT_READ_NO_CONFIG_PROBE", "false"));
    static final boolean CMDGEN_FULL_LAYOUT_BINDING2_PROBE_BUFFER_UINT_READ_CONST_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_BINDING2_PROBE_BUFFER_UINT_READ_CONST_PROBE", "false"));
    static final boolean CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_TINY_UINT_READ_NO_CONFIG_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_TINY_UINT_READ_NO_CONFIG_PROBE", "false"));
    static final boolean CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_PROBE_BUFFER_UINT_READ_NO_CONFIG_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_PROBE_BUFFER_UINT_READ_NO_CONFIG_PROBE", "false"));
    static final boolean CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_PROBE_BUFFER_UINT_READ_CONST_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_PROBE_BUFFER_UINT_READ_CONST_PROBE", "false"));
    static final boolean CMDGEN_SINGLE_BINDING1_TINY_UINT_READ_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_SINGLE_BINDING1_TINY_UINT_READ_PROBE", "false"));
    static final boolean CMDGEN_BINDING1_AS_BINDING0_TINY_UINT_READ_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_BINDING1_AS_BINDING0_TINY_UINT_READ_PROBE", "false"));
    static final boolean CMDGEN_FULL_LAYOUT_BINDING2_TINY_UINT_READ_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_BINDING2_TINY_UINT_READ_PROBE", "false"));
    static final boolean CMDGEN_RAW_METADATA_UVEC4_BINDING0_REAL_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_RAW_METADATA_UVEC4_BINDING0_REAL_PROBE", "false"));
    static final boolean CMDGEN_NO_IMPORT_COMPUTE_QUAD_COUNTS_ONLY_NO_WRITE_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_NO_IMPORT_COMPUTE_QUAD_COUNTS_ONLY_NO_WRITE_PROBE", "false"));
    static final boolean CMDGEN_NO_IMPORT_WRITE_COMMAND0_ONLY_NO_ATOMIC_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_NO_IMPORT_WRITE_COMMAND0_ONLY_NO_ATOMIC_PROBE", "false"));
    static final boolean CMDGEN_NO_IMPORT_ATOMIC_DRAWCOUNT_ONLY_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_NO_IMPORT_ATOMIC_DRAWCOUNT_ONLY_PROBE", "false"));
    static final boolean CMDGEN_NO_IMPORT_SINGLE_INVOCATION_REAL_COMMAND_NO_ATOMIC_PROBE = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_NO_IMPORT_SINGLE_INVOCATION_REAL_COMMAND_NO_ATOMIC_PROBE", "false"));
    static final boolean CMDGEN_USE_DENSE_LAYOUT_NOOP_SHADER = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_USE_DENSE_LAYOUT_NOOP_SHADER", "false"));
    static final boolean CMDGEN_DEBUG_READBACK = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_DEBUG_READBACK", "false"));
    static final boolean CMDGEN_DEBUG_READBACK_NO_COPY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_DEBUG_READBACK_NO_COPY", "false"));
    static final boolean CMDGEN_DEBUG_READBACK_DRAW_COUNT_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_DEBUG_READBACK_DRAW_COUNT_ONLY", "false"));
    static final boolean CMDGEN_DEBUG_READBACK_DRAW_COMMANDS_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_DEBUG_READBACK_DRAW_COMMANDS_ONLY", "false"));
    static final boolean CMDGEN_DEBUG_READBACK_LOG_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_DEBUG_READBACK_LOG_ONLY", "false"));
    static final boolean CMDGEN_DEBUG_READBACK_SCHEDULE_ENTER_ONLY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_DEBUG_READBACK_SCHEDULE_ENTER_ONLY", "false"));
    static final boolean CMDGEN_DEBUG_READBACK_NO_BARRIERS_NO_COPY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_DEBUG_READBACK_NO_BARRIERS_NO_COPY", "false"));
    static final boolean ENABLE_INDIRECT_DRAW = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_ENABLE_INDIRECT_DRAW", "false"));
    static final boolean FORCE_FULL_CMDGEN_DISPATCH_WITH_INDIRECT_DISABLED = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_FORCE_FULL_CMDGEN_DISPATCH_WITH_INDIRECT_DISABLED", "false"));
    static final boolean CMDGEN_SKIP_RENDER_DRAW_SUBMIT_AFTER_CMDGEN = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_SKIP_RENDER_DRAW_SUBMIT_AFTER_CMDGEN", "false"));
    static final boolean CMDGEN_WAIT_IDLE_AFTER_DISPATCH = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_CMDGEN_WAIT_IDLE_AFTER_DISPATCH", "false"));
    static final boolean RENDERLIST_SMOKE_ONE_ENTRY = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_RENDERLIST_SMOKE_ONE_ENTRY", "false"));

    static {
        List<String> activeDebugReadbackSubmodes = activeDebugReadbackSubmodeEnvVars();
        if (activeDebugReadbackSubmodes.size() > 1) {
            throw new IllegalStateException("only one cmdgen debug readback submode may be enabled at once: " + String.join(", ", activeDebugReadbackSubmodes));
        }
        List<String> activeIsolationModes = activeCmdgenIsolationModeEnvVars();
        if (activeIsolationModes.size() > 1) {
            throw new IllegalStateException("Multiple cmdgen isolation modes are active: " + String.join(", ", activeIsolationModes));
        }
    }

    static List<String> activeCmdgenIsolationModeEnvVars() {
        List<String> active = new ArrayList<>();
        addActiveCmdgenProbeEnvVar(active, CMDGEN_SHADER_READ_CONFIG_ONLY, "VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_CONFIG_ONLY");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_SHADER_READ_RENDERLIST_ONLY, "VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_ONLY");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_SHADER_READ_METADATA_ONLY, "VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_METADATA_ONLY");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_SHADER_WRITE_DRAWS_ONLY, "VOXY_VULKAN_BERYL_CMDGEN_SHADER_WRITE_DRAWS_ONLY");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_SHADER_READ_RENDERLIST_METADATA_NO_WRITE, "VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_METADATA_NO_WRITE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_NO_IMPORT_SINGLE_INVOCATION_REAL_COMMAND_NO_ATOMIC_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_NO_IMPORT_SINGLE_INVOCATION_REAL_COMMAND_NO_ATOMIC_PROBE");
        return active;
    }

    static List<String> activeDebugReadbackSubmodeEnvVars() {
        List<String> active = new ArrayList<>();
        addActiveCmdgenProbeEnvVar(active, CMDGEN_DEBUG_READBACK_NO_COPY, "VOXY_VULKAN_BERYL_CMDGEN_DEBUG_READBACK_NO_COPY");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_DEBUG_READBACK_DRAW_COUNT_ONLY, "VOXY_VULKAN_BERYL_CMDGEN_DEBUG_READBACK_DRAW_COUNT_ONLY");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_DEBUG_READBACK_DRAW_COMMANDS_ONLY, "VOXY_VULKAN_BERYL_CMDGEN_DEBUG_READBACK_DRAW_COMMANDS_ONLY");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_DEBUG_READBACK_LOG_ONLY, "VOXY_VULKAN_BERYL_CMDGEN_DEBUG_READBACK_LOG_ONLY");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_DEBUG_READBACK_SCHEDULE_ENTER_ONLY, "VOXY_VULKAN_BERYL_CMDGEN_DEBUG_READBACK_SCHEDULE_ENTER_ONLY");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_DEBUG_READBACK_NO_BARRIERS_NO_COPY, "VOXY_VULKAN_BERYL_CMDGEN_DEBUG_READBACK_NO_BARRIERS_NO_COPY");
        return active;
    }

    static String debugReadbackCopyModeName() {
        if (CMDGEN_DEBUG_READBACK_NO_COPY) return "no_copy";
        if (CMDGEN_DEBUG_READBACK_DRAW_COUNT_ONLY) return "draw_count_only";
        if (CMDGEN_DEBUG_READBACK_DRAW_COMMANDS_ONLY) return "draw_commands_only";
        if (CMDGEN_DEBUG_READBACK_LOG_ONLY) return "log_only";
        if (CMDGEN_DEBUG_READBACK_SCHEDULE_ENTER_ONLY) return "schedule_enter_only";
        if (CMDGEN_DEBUG_READBACK_NO_BARRIERS_NO_COPY) return "no_barriers_no_copy";
        return "default";
    }

    static List<String> activeCmdgenProbeEnvVars() {
        List<String> active = new ArrayList<>();
        addActiveCmdgenProbeEnvVar(active, CMDGEN_CREATE_ONLY, "VOXY_VULKAN_BERYL_CMDGEN_CREATE_ONLY");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_FINAL_COMMAND_WRITE_CONSTANT_AFTER_METADATA_READ_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_FINAL_COMMAND_WRITE_CONSTANT_AFTER_METADATA_READ_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_FINAL_COMMAND_WRITE_COMPUTED_VERTEX_COUNT_ONLY_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_FINAL_COMMAND_WRITE_COMPUTED_VERTEX_COUNT_ONLY_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_FINAL_COMMAND_WRITE_COMPUTED_FIRST_VERTEX_ONLY_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_FINAL_COMMAND_WRITE_COMPUTED_FIRST_VERTEX_ONLY_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_FINAL_COMMAND_WRITE_CLAMPED_COMPUTED_COMMAND_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_FINAL_COMMAND_WRITE_CLAMPED_COMPUTED_COMMAND_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_METADATA_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_METADATA_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_COMMAND_WRITE_AFTER_METADATA0_READ_NO_RENDERLIST_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_AFTER_METADATA0_READ_NO_RENDERLIST_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_COMMAND_WRITE_BEFORE_METADATA_READ_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_BEFORE_METADATA_READ_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_COMMAND_WRITE_AFTER_VISIBLECOUNT_READ_ONLY_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_AFTER_VISIBLECOUNT_READ_ONLY_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_COMMAND_WRITE_AFTER_INDIRECTLOOKUP0_READ_ONLY_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_AFTER_INDIRECTLOOKUP0_READ_ONLY_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_BRANCH_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_BRANCH_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_COMMAND_WRITE_AFTER_VISIBLECOUNT_BRANCH_ONLY_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_AFTER_VISIBLECOUNT_BRANCH_ONLY_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_DISPATCH_NOOP, "VOXY_VULKAN_BERYL_CMDGEN_DISPATCH_NOOP");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_DESCRIPTOR_NOOP_BIND_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_DESCRIPTOR_NOOP_BIND_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_UPLOAD_CONFIG_ONLY, "VOXY_VULKAN_BERYL_CMDGEN_UPLOAD_CONFIG_ONLY");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_CLEAR_OUTPUTS_ONLY, "VOXY_VULKAN_BERYL_CMDGEN_CLEAR_OUTPUTS_ONLY");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_BIND_FULL_ONLY, "VOXY_VULKAN_BERYL_CMDGEN_BIND_FULL_ONLY");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_SHADER_READ_RENDERLIST_ONLY, "VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_ONLY");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_SHADER_READ_RENDERLIST_HEADER_ONLY, "VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_HEADER_ONLY");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_SHADER_READ_RENDERLIST_COUNT_ONLY, "VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_COUNT_ONLY");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_SECTION_ID_ONLY, "VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_SECTION_ID_ONLY");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_QUAD_START_ONLY, "VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_QUAD_START_ONLY");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_QUAD_COUNT_ONLY, "VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_ENTRY0_QUAD_COUNT_ONLY");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_SHADER_READ_METADATA_ONLY, "VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_METADATA_ONLY");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_SHADER_WRITE_DRAWS_ONLY, "VOXY_VULKAN_BERYL_CMDGEN_SHADER_WRITE_DRAWS_ONLY");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_SHADER_READ_BINDING0_ONLY, "VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_BINDING0_ONLY");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_SHADER_READ_BINDING0_ONLY_NO_OUTPUT_WRITE, "VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_BINDING0_ONLY_NO_OUTPUT_WRITE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_SHADER_READ_BINDING1_ONLY, "VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_BINDING1_ONLY");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_SHADER_READ_CONFIG_ONLY, "VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_CONFIG_ONLY");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_SHADER_READ_RENDERLIST_METADATA_NO_WRITE, "VOXY_VULKAN_BERYL_CMDGEN_SHADER_READ_RENDERLIST_METADATA_NO_WRITE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_RENDERLIST_ALT_BUFFER_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_RENDERLIST_ALT_BUFFER_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_MINIMAL_TINY_SSBO_READ_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_MINIMAL_TINY_SSBO_READ_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_MINIMAL_RENDERLIST_MANUALUBO_READ_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_MINIMAL_RENDERLIST_MANUALUBO_READ_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_MINIMAL_CONFIG_READ_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_MINIMAL_CONFIG_READ_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_MINIMAL_CONFIG_BINDING0_READ_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_MINIMAL_CONFIG_BINDING0_READ_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_HARDCODED_READ_BINDING0_ONLY, "VOXY_VULKAN_BERYL_CMDGEN_HARDCODED_READ_BINDING0_ONLY");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_FULL_LAYOUT_NOOP_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_NOOP_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_DISPATCH_NOOP_SAME_LAYOUT, "VOXY_VULKAN_BERYL_CMDGEN_DISPATCH_NOOP_SAME_LAYOUT");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_FULL_LAYOUT_HARDCODED_BINDING0_READ_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_HARDCODED_BINDING0_READ_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_FULL_LAYOUT_CONFIG_BINDING0_READ_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_CONFIG_BINDING0_READ_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_BINDING0_CONFIG_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_SECTION_IMPORT_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_SECTION_IMPORT_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_CMDGEN_DECLS_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_CMDGEN_DECLS_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_SIMPLE_FLAG_BRANCH_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_SIMPLE_FLAG_BRANCH_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_READONLY_BRANCH_LADDER_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_READONLY_BRANCH_LADDER_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FULL_BRANCH_LADDER_NO_HELPERS_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FULL_BRANCH_LADDER_NO_HELPERS_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_HELPER_DECLS_UNUSED_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_HELPER_DECLS_UNUSED_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_HELPER_READONLY_USE_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_HELPER_READONLY_USE_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_BLOCK_NO_OUTPUT_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_BLOCK_NO_OUTPUT_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_COMMAND_WRITE_NO_DRAWCOUNT_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_COMMAND_WRITE_NO_DRAWCOUNT_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_DRAWCOUNT_WRITE_ONLY_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_DRAWCOUNT_WRITE_ONLY_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_DRAWCOUNT_LITERAL_ZERO_WRITE_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_DRAWCOUNT_LITERAL_ZERO_WRITE_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_DRAWCOUNT_LITERAL_ONE_WRITE_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_DRAWCOUNT_LITERAL_ONE_WRITE_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_DRAWCOUNT_NO_CONFIG_LITERAL_ZERO_WRITE_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_DRAWCOUNT_NO_CONFIG_LITERAL_ZERO_WRITE_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_DRAWCOUNT_DECLARED_NO_WRITE_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_DRAWCOUNT_DECLARED_NO_WRITE_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_NO_IMPORT_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_NO_IMPORT_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_NO_IMPORT_READ_METADATA0_ONLY_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_NO_IMPORT_READ_METADATA0_ONLY_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_NO_IMPORT_RAW_METADATA_UVEC4_BINDING1_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_NO_IMPORT_RAW_METADATA_UVEC4_BINDING1_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_NO_IMPORT_RAW_METADATA_UVEC4_BINDING1_TINY_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_NO_IMPORT_RAW_METADATA_UVEC4_BINDING1_TINY_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_FULL_LAYOUT_BINDING1_NO_READ_TINY_BIND_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_BINDING1_NO_READ_TINY_BIND_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_CONST_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_CONST_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_NO_CONFIG_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_BINDING1_TINY_UINT_READ_NO_CONFIG_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_FULL_LAYOUT_BINDING1_AND_BINDING2_UINT_READ_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_BINDING1_AND_BINDING2_UINT_READ_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_FULL_LAYOUT_BINDING2_PROBE_BUFFER_UINT_READ_NO_CONFIG_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_BINDING2_PROBE_BUFFER_UINT_READ_NO_CONFIG_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_FULL_LAYOUT_BINDING2_PROBE_BUFFER_UINT_READ_CONST_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_BINDING2_PROBE_BUFFER_UINT_READ_CONST_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_TINY_UINT_READ_NO_CONFIG_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_TINY_UINT_READ_NO_CONFIG_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_PROBE_BUFFER_UINT_READ_NO_CONFIG_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_PROBE_BUFFER_UINT_READ_NO_CONFIG_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_PROBE_BUFFER_UINT_READ_CONST_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_BINDING1_TINY_AND_BINDING2_PROBE_BUFFER_UINT_READ_CONST_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_SINGLE_BINDING1_TINY_UINT_READ_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_SINGLE_BINDING1_TINY_UINT_READ_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_BINDING1_AS_BINDING0_TINY_UINT_READ_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_BINDING1_AS_BINDING0_TINY_UINT_READ_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_FULL_LAYOUT_BINDING2_TINY_UINT_READ_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_FULL_LAYOUT_BINDING2_TINY_UINT_READ_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_RAW_METADATA_UVEC4_BINDING0_REAL_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_RAW_METADATA_UVEC4_BINDING0_REAL_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_NO_IMPORT_COMPUTE_QUAD_COUNTS_ONLY_NO_WRITE_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_NO_IMPORT_COMPUTE_QUAD_COUNTS_ONLY_NO_WRITE_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_NO_IMPORT_WRITE_COMMAND0_ONLY_NO_ATOMIC_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_NO_IMPORT_WRITE_COMMAND0_ONLY_NO_ATOMIC_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_NO_IMPORT_ATOMIC_DRAWCOUNT_ONLY_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_NO_IMPORT_ATOMIC_DRAWCOUNT_ONLY_PROBE");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_NO_IMPORT_SINGLE_INVOCATION_REAL_COMMAND_NO_ATOMIC_PROBE, "VOXY_VULKAN_BERYL_CMDGEN_NO_IMPORT_SINGLE_INVOCATION_REAL_COMMAND_NO_ATOMIC_PROBE");
        return active;
    }

    static void addActiveCmdgenProbeEnvVar(List<String> active, boolean enabled, String envVar) {
        if (enabled) {
            active.add(envVar);
        }
    }

    static void guardCmdgenProbeExclusivity() {
        guardCmdgenShaderSelectionExclusivity();
        List<String> active = activeCmdgenProbeEnvVars();
        if (active.size() > 1) {
            throw new IllegalStateException("Multiple cmdgen isolation/probe env flags are active: " + active);
        }
        if (active.size() == 1) {
            VulkanBerylDebugLog.once("active-cmdgen-probe", "active cmdgen probe: " + active.get(0));
        }
    }


    static List<String> activeCmdgenShaderSelectionEnvVars() {
        List<String> active = new ArrayList<>();
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_FINAL_COMMAND_WRITE_CONSTANT_AFTER_METADATA_READ_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_FINAL_COMMAND_WRITE_CONSTANT_AFTER_METADATA_READ_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_FINAL_COMMAND_WRITE_COMPUTED_VERTEX_COUNT_ONLY_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_FINAL_COMMAND_WRITE_COMPUTED_VERTEX_COUNT_ONLY_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_FINAL_COMMAND_WRITE_COMPUTED_FIRST_VERTEX_ONLY_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_FINAL_COMMAND_WRITE_COMPUTED_FIRST_VERTEX_ONLY_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_FINAL_COMMAND_WRITE_CLAMPED_COMPUTED_COMMAND_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_FINAL_COMMAND_WRITE_CLAMPED_COMPUTED_COMMAND_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_METADATA_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_METADATA_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_COMMAND_WRITE_AFTER_METADATA0_READ_NO_RENDERLIST_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_AFTER_METADATA0_READ_NO_RENDERLIST_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_COMMAND_WRITE_BEFORE_METADATA_READ_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_BEFORE_METADATA_READ_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_COMMAND_WRITE_AFTER_VISIBLECOUNT_READ_ONLY_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_AFTER_VISIBLECOUNT_READ_ONLY_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_COMMAND_WRITE_AFTER_INDIRECTLOOKUP0_READ_ONLY_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_AFTER_INDIRECTLOOKUP0_READ_ONLY_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_BRANCH_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_BRANCH_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_COMMAND_WRITE_AFTER_VISIBLECOUNT_BRANCH_ONLY_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_AFTER_VISIBLECOUNT_BRANCH_ONLY_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_BINDING0_CONFIG_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_SECTION_IMPORT_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_SECTION_IMPORT_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_CMDGEN_DECLS_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_CMDGEN_DECLS_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_SIMPLE_FLAG_BRANCH_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_SIMPLE_FLAG_BRANCH_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_READONLY_BRANCH_LADDER_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_READONLY_BRANCH_LADDER_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FULL_BRANCH_LADDER_NO_HELPERS_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FULL_BRANCH_LADDER_NO_HELPERS_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_HELPER_DECLS_UNUSED_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_HELPER_DECLS_UNUSED_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_HELPER_READONLY_USE_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_HELPER_READONLY_USE_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_BLOCK_NO_OUTPUT_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_BLOCK_NO_OUTPUT_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_COMMAND_WRITE_NO_DRAWCOUNT_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_COMMAND_WRITE_NO_DRAWCOUNT_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_DRAWCOUNT_WRITE_ONLY_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_DRAWCOUNT_WRITE_ONLY_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_DRAWCOUNT_LITERAL_ZERO_WRITE_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_DRAWCOUNT_LITERAL_ZERO_WRITE_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_DRAWCOUNT_LITERAL_ONE_WRITE_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_DRAWCOUNT_LITERAL_ONE_WRITE_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_DRAWCOUNT_NO_CONFIG_LITERAL_ZERO_WRITE_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_DRAWCOUNT_NO_CONFIG_LITERAL_ZERO_WRITE_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_STANDALONE_DRAWCOUNT_DECLARED_NO_WRITE_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_DRAWCOUNT_DECLARED_NO_WRITE_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER");
        addActiveCmdgenProbeEnvVar(active, CMDGEN_USE_DENSE_LAYOUT_NOOP_SHADER, "VOXY_VULKAN_BERYL_CMDGEN_USE_DENSE_LAYOUT_NOOP_SHADER");
        return active;
    }

    static void guardCmdgenShaderSelectionExclusivity() {
        List<String> active = activeCmdgenShaderSelectionEnvVars();
        if (active.size() > 1) {
            throw new IllegalStateException("Only one normal cmdgen shader-selection env flag may be active at once: " + active);
        }
        if (active.size() == 1) {
            VulkanBerylDebugLog.once("active-cmdgen-shader-selection", "active cmdgen shader-selection mode: " + active.get(0));
        }
    }

    static String activeCmdgenShaderResource() {
        if (CMDGEN_USE_FINAL_COMMAND_WRITE_CONSTANT_AFTER_METADATA_READ_SHADER) return CMDGEN_FINAL_COMMAND_WRITE_CONSTANT_AFTER_METADATA_READ_SHADER_RESOURCE;
        if (CMDGEN_USE_FINAL_COMMAND_WRITE_COMPUTED_VERTEX_COUNT_ONLY_SHADER) return CMDGEN_FINAL_COMMAND_WRITE_COMPUTED_VERTEX_COUNT_ONLY_SHADER_RESOURCE;
        if (CMDGEN_USE_FINAL_COMMAND_WRITE_COMPUTED_FIRST_VERTEX_ONLY_SHADER) return CMDGEN_FINAL_COMMAND_WRITE_COMPUTED_FIRST_VERTEX_ONLY_SHADER_RESOURCE;
        if (CMDGEN_USE_FINAL_COMMAND_WRITE_CLAMPED_COMPUTED_COMMAND_SHADER) return CMDGEN_FINAL_COMMAND_WRITE_CLAMPED_COMPUTED_COMMAND_SHADER_RESOURCE;
        if (CMDGEN_USE_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_METADATA_SHADER) return CMDGEN_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_METADATA_SHADER_RESOURCE;
        if (CMDGEN_USE_COMMAND_WRITE_AFTER_METADATA0_READ_NO_RENDERLIST_SHADER) return CMDGEN_COMMAND_WRITE_AFTER_METADATA0_READ_NO_RENDERLIST_SHADER_RESOURCE;
        if (CMDGEN_USE_COMMAND_WRITE_BEFORE_METADATA_READ_SHADER) return CMDGEN_COMMAND_WRITE_BEFORE_METADATA_READ_SHADER_RESOURCE;
        if (CMDGEN_USE_COMMAND_WRITE_AFTER_VISIBLECOUNT_READ_ONLY_SHADER) return CMDGEN_COMMAND_WRITE_AFTER_VISIBLECOUNT_READ_ONLY_SHADER_RESOURCE;
        if (CMDGEN_USE_COMMAND_WRITE_AFTER_INDIRECTLOOKUP0_READ_ONLY_SHADER) return CMDGEN_COMMAND_WRITE_AFTER_INDIRECTLOOKUP0_READ_ONLY_SHADER_RESOURCE;
        if (CMDGEN_USE_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_BRANCH_SHADER) return CMDGEN_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_BRANCH_SHADER_RESOURCE;
        if (CMDGEN_USE_COMMAND_WRITE_AFTER_VISIBLECOUNT_BRANCH_ONLY_SHADER) return CMDGEN_COMMAND_WRITE_AFTER_VISIBLECOUNT_BRANCH_ONLY_SHADER_RESOURCE;
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_SHADER) return CMDGEN_STANDALONE_BINDING0_CONFIG_READ_SHADER_RESOURCE;
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_SECTION_IMPORT_SHADER) return CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_SECTION_IMPORT_SHADER_RESOURCE;
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_CMDGEN_DECLS_SHADER) return CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_CMDGEN_DECLS_SHADER_RESOURCE;
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_SIMPLE_FLAG_BRANCH_SHADER) return CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_SIMPLE_FLAG_BRANCH_SHADER_RESOURCE;
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_READONLY_BRANCH_LADDER_SHADER) return CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_READONLY_BRANCH_LADDER_SHADER_RESOURCE;
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FULL_BRANCH_LADDER_NO_HELPERS_SHADER) return CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_FULL_BRANCH_LADDER_NO_HELPERS_SHADER_RESOURCE;
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_HELPER_DECLS_UNUSED_SHADER) return CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_HELPER_DECLS_UNUSED_SHADER_RESOURCE;
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_HELPER_READONLY_USE_SHADER) return CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_HELPER_READONLY_USE_SHADER_RESOURCE;
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_BLOCK_NO_OUTPUT_SHADER) return CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_FINAL_BLOCK_NO_OUTPUT_SHADER_RESOURCE;
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_COMMAND_WRITE_NO_DRAWCOUNT_SHADER) return CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_FINAL_COMMAND_WRITE_NO_DRAWCOUNT_SHADER_RESOURCE;
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_DRAWCOUNT_WRITE_ONLY_SHADER) return CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_FINAL_DRAWCOUNT_WRITE_ONLY_SHADER_RESOURCE;
        if (CMDGEN_USE_STANDALONE_DRAWCOUNT_LITERAL_ZERO_WRITE_SHADER) return CMDGEN_STANDALONE_DRAWCOUNT_LITERAL_ZERO_WRITE_SHADER_RESOURCE;
        if (CMDGEN_USE_STANDALONE_DRAWCOUNT_LITERAL_ONE_WRITE_SHADER) return CMDGEN_STANDALONE_DRAWCOUNT_LITERAL_ONE_WRITE_SHADER_RESOURCE;
        if (CMDGEN_USE_STANDALONE_DRAWCOUNT_NO_CONFIG_LITERAL_ZERO_WRITE_SHADER) return CMDGEN_STANDALONE_DRAWCOUNT_NO_CONFIG_LITERAL_ZERO_WRITE_SHADER_RESOURCE;
        if (CMDGEN_USE_STANDALONE_DRAWCOUNT_DECLARED_NO_WRITE_SHADER) return CMDGEN_STANDALONE_DRAWCOUNT_DECLARED_NO_WRITE_SHADER_RESOURCE;
        if (CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER) return CMDGEN_NO_DRAWCOUNT_WRITE_SHADER_RESOURCE;
        if (CMDGEN_USE_DENSE_LAYOUT_NOOP_SHADER) return CMDGEN_DENSE_LAYOUT_NOOP_SHADER_RESOURCE;
        return CMDGEN_SHADER_RESOURCE;
    }

    static String activeCmdgenShaderName() {
        if (CMDGEN_USE_FINAL_COMMAND_WRITE_CONSTANT_AFTER_METADATA_READ_SHADER) return CMDGEN_FINAL_COMMAND_WRITE_CONSTANT_AFTER_METADATA_READ_SHADER_NAME;
        if (CMDGEN_USE_FINAL_COMMAND_WRITE_COMPUTED_VERTEX_COUNT_ONLY_SHADER) return CMDGEN_FINAL_COMMAND_WRITE_COMPUTED_VERTEX_COUNT_ONLY_SHADER_NAME;
        if (CMDGEN_USE_FINAL_COMMAND_WRITE_COMPUTED_FIRST_VERTEX_ONLY_SHADER) return CMDGEN_FINAL_COMMAND_WRITE_COMPUTED_FIRST_VERTEX_ONLY_SHADER_NAME;
        if (CMDGEN_USE_FINAL_COMMAND_WRITE_CLAMPED_COMPUTED_COMMAND_SHADER) return CMDGEN_FINAL_COMMAND_WRITE_CLAMPED_COMPUTED_COMMAND_SHADER_NAME;
        if (CMDGEN_USE_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_METADATA_SHADER) return CMDGEN_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_METADATA_SHADER_NAME;
        if (CMDGEN_USE_COMMAND_WRITE_AFTER_METADATA0_READ_NO_RENDERLIST_SHADER) return CMDGEN_COMMAND_WRITE_AFTER_METADATA0_READ_NO_RENDERLIST_SHADER_NAME;
        if (CMDGEN_USE_COMMAND_WRITE_BEFORE_METADATA_READ_SHADER) return CMDGEN_COMMAND_WRITE_BEFORE_METADATA_READ_SHADER_NAME;
        if (CMDGEN_USE_COMMAND_WRITE_AFTER_VISIBLECOUNT_READ_ONLY_SHADER) return CMDGEN_COMMAND_WRITE_AFTER_VISIBLECOUNT_READ_ONLY_SHADER_NAME;
        if (CMDGEN_USE_COMMAND_WRITE_AFTER_INDIRECTLOOKUP0_READ_ONLY_SHADER) return CMDGEN_COMMAND_WRITE_AFTER_INDIRECTLOOKUP0_READ_ONLY_SHADER_NAME;
        if (CMDGEN_USE_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_BRANCH_SHADER) return CMDGEN_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_BRANCH_SHADER_NAME;
        if (CMDGEN_USE_COMMAND_WRITE_AFTER_VISIBLECOUNT_BRANCH_ONLY_SHADER) return CMDGEN_COMMAND_WRITE_AFTER_VISIBLECOUNT_BRANCH_ONLY_SHADER_NAME;
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_SHADER) return CMDGEN_STANDALONE_BINDING0_CONFIG_READ_SHADER_NAME;
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_SECTION_IMPORT_SHADER) return CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_SECTION_IMPORT_SHADER_NAME;
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_CMDGEN_DECLS_SHADER) return CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_CMDGEN_DECLS_SHADER_NAME;
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_SIMPLE_FLAG_BRANCH_SHADER) return CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_SIMPLE_FLAG_BRANCH_SHADER_NAME;
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_READONLY_BRANCH_LADDER_SHADER) return CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_READONLY_BRANCH_LADDER_SHADER_NAME;
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FULL_BRANCH_LADDER_NO_HELPERS_SHADER) return CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_FULL_BRANCH_LADDER_NO_HELPERS_SHADER_NAME;
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_HELPER_DECLS_UNUSED_SHADER) return CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_HELPER_DECLS_UNUSED_SHADER_NAME;
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_HELPER_READONLY_USE_SHADER) return CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_HELPER_READONLY_USE_SHADER_NAME;
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_BLOCK_NO_OUTPUT_SHADER) return CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_FINAL_BLOCK_NO_OUTPUT_SHADER_NAME;
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_COMMAND_WRITE_NO_DRAWCOUNT_SHADER) return CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_FINAL_COMMAND_WRITE_NO_DRAWCOUNT_SHADER_NAME;
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_DRAWCOUNT_WRITE_ONLY_SHADER) return CMDGEN_STANDALONE_BINDING0_CONFIG_WITH_FINAL_DRAWCOUNT_WRITE_ONLY_SHADER_NAME;
        if (CMDGEN_USE_STANDALONE_DRAWCOUNT_LITERAL_ZERO_WRITE_SHADER) return CMDGEN_STANDALONE_DRAWCOUNT_LITERAL_ZERO_WRITE_SHADER_NAME;
        if (CMDGEN_USE_STANDALONE_DRAWCOUNT_LITERAL_ONE_WRITE_SHADER) return CMDGEN_STANDALONE_DRAWCOUNT_LITERAL_ONE_WRITE_SHADER_NAME;
        if (CMDGEN_USE_STANDALONE_DRAWCOUNT_NO_CONFIG_LITERAL_ZERO_WRITE_SHADER) return CMDGEN_STANDALONE_DRAWCOUNT_NO_CONFIG_LITERAL_ZERO_WRITE_SHADER_NAME;
        if (CMDGEN_USE_STANDALONE_DRAWCOUNT_DECLARED_NO_WRITE_SHADER) return CMDGEN_STANDALONE_DRAWCOUNT_DECLARED_NO_WRITE_SHADER_NAME;
        if (CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER) return CMDGEN_NO_DRAWCOUNT_WRITE_SHADER_NAME;
        if (CMDGEN_USE_DENSE_LAYOUT_NOOP_SHADER) return CMDGEN_DENSE_LAYOUT_NOOP_SHADER_NAME;
        return CMDGEN_SHADER_NAME;
    }

    static String activeCmdgenShaderSelectionEnvVar() {
        if (CMDGEN_USE_FINAL_COMMAND_WRITE_CONSTANT_AFTER_METADATA_READ_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_FINAL_COMMAND_WRITE_CONSTANT_AFTER_METADATA_READ_SHADER";
        if (CMDGEN_USE_FINAL_COMMAND_WRITE_COMPUTED_VERTEX_COUNT_ONLY_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_FINAL_COMMAND_WRITE_COMPUTED_VERTEX_COUNT_ONLY_SHADER";
        if (CMDGEN_USE_FINAL_COMMAND_WRITE_COMPUTED_FIRST_VERTEX_ONLY_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_FINAL_COMMAND_WRITE_COMPUTED_FIRST_VERTEX_ONLY_SHADER";
        if (CMDGEN_USE_FINAL_COMMAND_WRITE_CLAMPED_COMPUTED_COMMAND_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_FINAL_COMMAND_WRITE_CLAMPED_COMPUTED_COMMAND_SHADER";
        if (CMDGEN_USE_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_METADATA_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_METADATA_SHADER";
        if (CMDGEN_USE_COMMAND_WRITE_AFTER_METADATA0_READ_NO_RENDERLIST_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_AFTER_METADATA0_READ_NO_RENDERLIST_SHADER";
        if (CMDGEN_USE_COMMAND_WRITE_BEFORE_METADATA_READ_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_BEFORE_METADATA_READ_SHADER";
        if (CMDGEN_USE_COMMAND_WRITE_AFTER_VISIBLECOUNT_READ_ONLY_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_AFTER_VISIBLECOUNT_READ_ONLY_SHADER";
        if (CMDGEN_USE_COMMAND_WRITE_AFTER_INDIRECTLOOKUP0_READ_ONLY_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_AFTER_INDIRECTLOOKUP0_READ_ONLY_SHADER";
        if (CMDGEN_USE_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_BRANCH_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_BRANCH_SHADER";
        if (CMDGEN_USE_COMMAND_WRITE_AFTER_VISIBLECOUNT_BRANCH_ONLY_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_COMMAND_WRITE_AFTER_VISIBLECOUNT_BRANCH_ONLY_SHADER";
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_SHADER";
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_SECTION_IMPORT_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_SECTION_IMPORT_SHADER";
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_CMDGEN_DECLS_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_CMDGEN_DECLS_SHADER";
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_SIMPLE_FLAG_BRANCH_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_SIMPLE_FLAG_BRANCH_SHADER";
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_READONLY_BRANCH_LADDER_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_READONLY_BRANCH_LADDER_SHADER";
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FULL_BRANCH_LADDER_NO_HELPERS_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FULL_BRANCH_LADDER_NO_HELPERS_SHADER";
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_HELPER_DECLS_UNUSED_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_HELPER_DECLS_UNUSED_SHADER";
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_HELPER_READONLY_USE_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_HELPER_READONLY_USE_SHADER";
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_BLOCK_NO_OUTPUT_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_BLOCK_NO_OUTPUT_SHADER";
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_COMMAND_WRITE_NO_DRAWCOUNT_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_COMMAND_WRITE_NO_DRAWCOUNT_SHADER";
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_DRAWCOUNT_WRITE_ONLY_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_DRAWCOUNT_WRITE_ONLY_SHADER";
        if (CMDGEN_USE_STANDALONE_DRAWCOUNT_LITERAL_ZERO_WRITE_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_DRAWCOUNT_LITERAL_ZERO_WRITE_SHADER";
        if (CMDGEN_USE_STANDALONE_DRAWCOUNT_LITERAL_ONE_WRITE_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_DRAWCOUNT_LITERAL_ONE_WRITE_SHADER";
        if (CMDGEN_USE_STANDALONE_DRAWCOUNT_NO_CONFIG_LITERAL_ZERO_WRITE_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_DRAWCOUNT_NO_CONFIG_LITERAL_ZERO_WRITE_SHADER";
        if (CMDGEN_USE_STANDALONE_DRAWCOUNT_DECLARED_NO_WRITE_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_STANDALONE_DRAWCOUNT_DECLARED_NO_WRITE_SHADER";
        if (CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER";
        if (CMDGEN_USE_DENSE_LAYOUT_NOOP_SHADER) return "VOXY_VULKAN_BERYL_CMDGEN_USE_DENSE_LAYOUT_NOOP_SHADER";
        return null;
    }

    static String activeCmdgenShaderMode() {
        if (CMDGEN_USE_FINAL_COMMAND_WRITE_CONSTANT_AFTER_METADATA_READ_SHADER) return "final_command_write_constant_after_metadata_read";
        if (CMDGEN_USE_FINAL_COMMAND_WRITE_COMPUTED_VERTEX_COUNT_ONLY_SHADER) return "final_command_write_computed_vertex_count_only";
        if (CMDGEN_USE_FINAL_COMMAND_WRITE_COMPUTED_FIRST_VERTEX_ONLY_SHADER) return "final_command_write_computed_first_vertex_only";
        if (CMDGEN_USE_FINAL_COMMAND_WRITE_CLAMPED_COMPUTED_COMMAND_SHADER) return "final_command_write_clamped_computed_command";
        if (CMDGEN_USE_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_METADATA_SHADER) return "command_write_after_renderlist_read_no_metadata";
        if (CMDGEN_USE_COMMAND_WRITE_AFTER_METADATA0_READ_NO_RENDERLIST_SHADER) return "command_write_after_metadata0_read_no_renderlist";
        if (CMDGEN_USE_COMMAND_WRITE_BEFORE_METADATA_READ_SHADER) return "command_write_before_metadata_read";
        if (CMDGEN_USE_COMMAND_WRITE_AFTER_VISIBLECOUNT_READ_ONLY_SHADER) return "command_write_after_visiblecount_read_only";
        if (CMDGEN_USE_COMMAND_WRITE_AFTER_INDIRECTLOOKUP0_READ_ONLY_SHADER) return "command_write_after_indirectlookup0_read_only";
        if (CMDGEN_USE_COMMAND_WRITE_AFTER_RENDERLIST_READ_NO_BRANCH_SHADER) return "command_write_after_renderlist_read_no_branch";
        if (CMDGEN_USE_COMMAND_WRITE_AFTER_VISIBLECOUNT_BRANCH_ONLY_SHADER) return "command_write_after_visiblecount_branch_only";
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_SHADER) return "standalone binding0+config";
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_SECTION_IMPORT_SHADER) return "standalone binding0+config with section import";
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_CMDGEN_DECLS_SHADER) return "standalone binding0+config with cmdgen declarations";
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_SIMPLE_FLAG_BRANCH_SHADER) return "standalone binding0+config with simple flag branch";
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_READONLY_BRANCH_LADDER_SHADER) return "standalone binding0+config with read-only branch ladder";
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FULL_BRANCH_LADDER_NO_HELPERS_SHADER) return "standalone binding0+config with full branch ladder no helpers";
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_HELPER_DECLS_UNUSED_SHADER) return "standalone binding0+config with helper declarations unused";
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_HELPER_READONLY_USE_SHADER) return "standalone binding0+config with helper read-only use";
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_BLOCK_NO_OUTPUT_SHADER) return "standalone binding0+config with final block no output";
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_COMMAND_WRITE_NO_DRAWCOUNT_SHADER) return "standalone binding0+config with final command write no drawcount";
        if (CMDGEN_USE_STANDALONE_BINDING0_CONFIG_WITH_FINAL_DRAWCOUNT_WRITE_ONLY_SHADER) return "standalone binding0+config with final drawcount write only";
        if (CMDGEN_USE_STANDALONE_DRAWCOUNT_LITERAL_ZERO_WRITE_SHADER) return "standalone drawcount literal zero write";
        if (CMDGEN_USE_STANDALONE_DRAWCOUNT_LITERAL_ONE_WRITE_SHADER) return "standalone drawcount literal one write";
        if (CMDGEN_USE_STANDALONE_DRAWCOUNT_NO_CONFIG_LITERAL_ZERO_WRITE_SHADER) return "standalone drawcount no-config literal zero write";
        if (CMDGEN_USE_STANDALONE_DRAWCOUNT_DECLARED_NO_WRITE_SHADER) return "standalone drawcount declared no write";
        if (CMDGEN_USE_FULL_NO_DRAWCOUNT_WRITE_SHADER) return "full cmdgen command writes with GPU drawCount stores disabled";
        if (CMDGEN_USE_DENSE_LAYOUT_NOOP_SHADER) return "dense layout noop — binds all 6 descriptors, reads nothing, writes nothing";
        return "normal cmdgen.comp";
    }

    private VulkanBerylCmdgenDiagnostics() {
    }

    static void ensureLoaded() {
    }
}
