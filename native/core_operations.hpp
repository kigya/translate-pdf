#pragma once
// Vendor and file-system boundary. The application uses the real entrypoints.
// Host fault tests replace one pointer under exclusive, single-threaded ownership
// and restore it before the next scenario. No alternate core implementation.
#include "llama.h"
#include "fpdfview.h"
#include "fpdf_text.h"
#include "fpdf_edit.h"
#include "fpdf_save.h"
#include "fpdf_ppo.h"
#include "fpdf_annot.h"
#include <cstdio>
#include <unistd.h>
namespace translatepdf::detail::ops {
extern decltype(&::FPDFAnnot_SetColor) FPDFAnnot_SetColor;
extern decltype(&::FPDFAnnot_SetFlags) FPDFAnnot_SetFlags;
extern decltype(&::FPDFAnnot_SetRect) FPDFAnnot_SetRect;
extern decltype(&::FPDFAnnot_SetStringValue) FPDFAnnot_SetStringValue;
extern decltype(&::FPDFBitmap_CreateEx) FPDFBitmap_CreateEx;
extern decltype(&::FPDFBitmap_Destroy) FPDFBitmap_Destroy;
extern decltype(&::FPDFBitmap_FillRect) FPDFBitmap_FillRect;
extern decltype(&::FPDFFont_Close) FPDFFont_Close;
extern decltype(&::FPDFFont_GetGlyphWidth) FPDFFont_GetGlyphWidth;
extern decltype(&::FPDFPageObj_CreateTextObj) FPDFPageObj_CreateTextObj;
extern decltype(&::FPDFPageObj_Destroy) FPDFPageObj_Destroy;
extern decltype(&::FPDFPageObj_GetBounds) FPDFPageObj_GetBounds;
extern decltype(&::FPDFPageObj_GetMatrix) FPDFPageObj_GetMatrix;
extern decltype(&::FPDFPageObj_GetType) FPDFPageObj_GetType;
extern decltype(&::FPDFPageObj_SetFillColor) FPDFPageObj_SetFillColor;
extern decltype(&::FPDFPageObj_Transform) FPDFPageObj_Transform;
extern decltype(&::FPDFPage_CloseAnnot) FPDFPage_CloseAnnot;
extern decltype(&::FPDFPage_CountObjects) FPDFPage_CountObjects;
extern decltype(&::FPDFPage_CreateAnnot) FPDFPage_CreateAnnot;
extern decltype(&::FPDFPage_GenerateContent) FPDFPage_GenerateContent;
extern decltype(&::FPDFPage_GetObject) FPDFPage_GetObject;
extern decltype(&::FPDFPage_GetRotation) FPDFPage_GetRotation;
extern decltype(&::FPDFPage_InsertObject) FPDFPage_InsertObject;
extern decltype(&::FPDFPage_RemoveObject) FPDFPage_RemoveObject;
extern decltype(&::FPDFTextObj_GetFont) FPDFTextObj_GetFont;
extern decltype(&::FPDFTextObj_GetFontSize) FPDFTextObj_GetFontSize;
extern decltype(&::FPDFTextObj_GetText) FPDFTextObj_GetText;
extern decltype(&::FPDFText_ClosePage) FPDFText_ClosePage;
extern decltype(&::FPDFText_LoadFont) FPDFText_LoadFont;
extern decltype(&::FPDFText_LoadPage) FPDFText_LoadPage;
extern decltype(&::FPDFText_SetText) FPDFText_SetText;
extern decltype(&::FPDF_CloseDocument) FPDF_CloseDocument;
extern decltype(&::FPDF_ClosePage) FPDF_ClosePage;
extern decltype(&::FPDF_CreateNewDocument) FPDF_CreateNewDocument;
extern decltype(&::FPDF_GetLastError) FPDF_GetLastError;
extern decltype(&::FPDF_GetPageCount) FPDF_GetPageCount;
extern decltype(&::FPDF_GetPageHeightF) FPDF_GetPageHeightF;
extern decltype(&::FPDF_GetPageWidthF) FPDF_GetPageWidthF;
extern decltype(&::FPDF_ImportPagesByIndex) FPDF_ImportPagesByIndex;
extern decltype(&::FPDF_InitLibrary) FPDF_InitLibrary;
extern decltype(&::FPDF_LoadDocument) FPDF_LoadDocument;
extern decltype(&::FPDF_LoadPage) FPDF_LoadPage;
extern decltype(&::FPDF_RenderPageBitmap) FPDF_RenderPageBitmap;
extern decltype(&::FPDF_SaveAsCopy) FPDF_SaveAsCopy;
extern decltype(&::llama_backend_init) llama_backend_init;
extern decltype(&::llama_batch_get_one) llama_batch_get_one;
extern decltype(&::llama_chat_apply_template) llama_chat_apply_template;
extern decltype(&::llama_context_default_params) llama_context_default_params;
extern decltype(&::llama_decode) llama_decode;
extern decltype(&::llama_free) llama_free;
extern decltype(&::llama_get_memory) llama_get_memory;
extern decltype(&::llama_init_from_model) llama_init_from_model;
extern decltype(&::llama_log_set) llama_log_set;
extern decltype(&::llama_memory_clear) llama_memory_clear;
extern decltype(&::llama_model_chat_template) llama_model_chat_template;
extern decltype(&::llama_model_default_params) llama_model_default_params;
extern decltype(&::llama_model_free) llama_model_free;
extern decltype(&::llama_model_get_vocab) llama_model_get_vocab;
extern decltype(&::llama_model_has_encoder) llama_model_has_encoder;
extern decltype(&::llama_model_load_from_file) llama_model_load_from_file;
extern decltype(&::llama_model_size) llama_model_size;
extern decltype(&::llama_n_ctx) llama_n_ctx;
extern decltype(&::llama_n_threads) llama_n_threads;
extern decltype(&::llama_sampler_chain_add) llama_sampler_chain_add;
extern decltype(&::llama_sampler_chain_default_params) llama_sampler_chain_default_params;
extern decltype(&::llama_sampler_chain_init) llama_sampler_chain_init;
extern decltype(&::llama_sampler_free) llama_sampler_free;
extern decltype(&::llama_sampler_init_dist) llama_sampler_init_dist;
extern decltype(&::llama_sampler_init_greedy) llama_sampler_init_greedy;
extern decltype(&::llama_sampler_init_penalties) llama_sampler_init_penalties;
extern decltype(&::llama_sampler_init_temp) llama_sampler_init_temp;
extern decltype(&::llama_sampler_init_top_k) llama_sampler_init_top_k;
extern decltype(&::llama_sampler_init_top_p) llama_sampler_init_top_p;
extern decltype(&::llama_sampler_sample) llama_sampler_sample;
extern decltype(&::llama_token_to_piece) llama_token_to_piece;
extern decltype(&::llama_tokenize) llama_tokenize;
extern decltype(&::llama_vocab_bos) llama_vocab_bos;
extern decltype(&::llama_vocab_is_eog) llama_vocab_is_eog;
extern decltype(&::llama_vocab_n_tokens) llama_vocab_n_tokens;
extern decltype(&::fopen) fopen;
extern decltype(&::fwrite) fwrite;
extern decltype(&::fflush) fflush;
extern decltype(&::fsync) fsync;
extern decltype(&::fclose) fclose;
extern decltype(&::rename) rename;
}
