#!/usr/bin/env python3
"""Finite grammar for the complete compiler-lowered private registry literal.

Every fresh array allocation, storage derivation, bounded index, String literal,
initializing store and final Dictionary/global store is retained. This is an
initializer construction fact under the bound Swift ABI/library contracts, not
complete observer equivalence or application approval.
"""
from pathlib import Path
import re
import subprocess
import certification as cert
import semantic_contracts as sil
import observer_callback_contracts as callbacks


ALLOC='$ss27_allocateUninitializedArrayySayxG_BptBwlF'
ALLOC_TYPE='$@convention(thin) <τ_0_0> (Builtin.Word) -> (@owned Array<τ_0_0>, Builtin.RawPointer)'
FINALIZE='$ss27_finalizeUninitializedArrayySayxGABnlF'
FINALIZE_TYPE='$@convention(thin) <τ_0_0> (@owned Array<τ_0_0>) -> @owned Array<τ_0_0>'
STRING='$sSS21_builtinStringLiteral17utf8CodeUnitCount7isASCIISSBp_BwBi1_tcfC'
STRING_TYPE='$@convention(method) (Builtin.RawPointer, Builtin.Word, Builtin.Int1, @thin String.Type) -> @owned String'
DICTIONARY='$sSD17dictionaryLiteralSDyxq_Gx_q_td_tcfC'
DICTIONARY_TYPE='$@convention(method) <τ_0_0, τ_0_1 where τ_0_0 : Hashable> (@owned Array<(τ_0_0, τ_0_1)>, @thin Dictionary<τ_0_0, τ_0_1>.Type) -> @owned Dictionary<τ_0_0, τ_0_1>'


class Program:
    def __init__(self):self.lines=['bb0(%0 : $Builtin.RawPointer):'];self.next=1
    def emit(self,text):self.lines.append(text)
    def value(self,text):
        value='%'+str(self.next);self.next+=1;self.emit(value+' = '+text);return value
    def string(self,text):
        if not re.fullmatch(r'[A-Za-z0-9:_-]+',text):raise ValueError('Unknown registry literal alphabet')
        literal=self.value('string_literal utf8 "'+text+'"')
        count=self.value('integer_literal $Builtin.Word, '+str(len(text.encode())))
        ascii_=self.value('integer_literal $Builtin.Int1, -1')
        type_=self.value('metatype $@thin String.Type')
        function=self.value('function_ref @'+STRING+' : '+STRING_TYPE)
        return self.value(f'apply {function}({literal}, {count}, {ascii_}, {type_}) : '+STRING_TYPE)
    def array(self,values,element_type,initialize):
        if not values:raise ValueError('Empty registry Array is unsupported by this finite grammar')
        count=self.value('integer_literal $Builtin.Word, '+str(len(values)))
        function=self.value('function_ref @'+ALLOC+' : '+ALLOC_TYPE)
        allocation=self.value(f'apply {function}<{element_type}>({count}) : '+ALLOC_TYPE)
        array='%'+str(self.next);pointer='%'+str(self.next+1);self.next+=2
        self.emit(f'({array}, {pointer}) = destructure_tuple {allocation}')
        borrow=self.value('begin_borrow '+array)
        buffer=self.value(f'struct_extract {borrow}, #Array._buffer')
        storage=self.value(f'struct_extract {buffer}, #_ArrayBuffer._storage')
        raw=self.value(f'struct_extract {storage}, #_BridgeStorage.rawValue')
        cast=self.value(f'unchecked_ref_cast {raw} to $__ContiguousArrayStorageBase')
        base=self.value(f'ref_tail_addr {cast}, ${element_type}')
        for index,item in enumerate(values):
            address=base
            if index:
                offset=self.value('integer_literal $Builtin.Word, '+str(index))
                address=self.value(f'index_addr {base}, {offset}')
            initialize(address,item)
        self.emit('end_borrow '+borrow)
        finalize=self.value('function_ref @'+FINALIZE+' : '+FINALIZE_TYPE)
        return self.value(f'apply {finalize}<{element_type}>({array}) : '+FINALIZE_TYPE)


def expected_program(registry,global_symbol):
    if not registry:raise ValueError('Empty source registry has no rule')
    if any(not outcomes or len(outcomes)!=len(set(outcomes)) for outcomes in registry.values()):
        raise ValueError('Registry outcome lists must be nonempty and unique')
    program=Program();program.emit('alloc_global @'+global_symbol)
    destination=program.value('global_addr @'+global_symbol+' : $*Dictionary<String, Array<String>>')
    def outcome(address,text):program.emit('store '+program.string(text)+' to [init] '+address)
    def entry(address,item):
        key,outcomes=item
        key_address=program.value('tuple_element_addr '+address+', 0')
        values_address=program.value('tuple_element_addr '+address+', 1')
        program.emit('store '+program.string(key)+' to [init] '+key_address)
        array=program.array(outcomes,'String',outcome)
        program.emit('store '+array+' to [init] '+values_address)
    array=program.array(sorted(registry.items()),'(String, Array<String>)',entry)
    type_=program.value('metatype $@thin Dictionary<String, Array<String>>.Type')
    function=program.value('function_ref @'+DICTIONARY+' : '+DICTIONARY_TYPE)
    dictionary=program.value(f'apply {function}<String, Array<String>>({array}, {type_}) : '+DICTIONARY_TYPE)
    program.emit('store '+dictionary+' to [init] '+destination)
    void=program.value('tuple ()');program.emit('return '+void)
    return program.lines


def prove(inventory,module,registry):
    result={'privateRegistryConstructionProgramProved':False,'failures':[],
            'observerNoninterferenceCertified':False,'semanticEquivalenceCertified':False,'applicationGateApproved':False}
    try:
        prefix=f'$s{len(module)}{module}';roots=[];n=callbacks.node
        # Identifier word substitution can compress __fbRegistry as well as the
        # private discriminator. Select by the complete compiler-decoded tree.
        for symbol,function in inventory.items():
            if not symbol.startswith(prefix) or not symbol.endswith('LL_WZ'):continue
            try:
                tree,demangling=callbacks.demangled(symbol)
                scope=tree['children'][0]['children'][1]['children'][0]['children'][0]['text']
            except (ValueError,KeyError,IndexError,TypeError):continue
            expected=n('Global',n('GlobalVariableOnceFunction',n('Module',text=module),
                n('GlobalVariableOnceDeclList',n('PrivateDeclName',n('Identifier',text=scope),n('Identifier',text='__fbRegistry')))))
            if scope and re.fullmatch(r'_[A-F0-9]+',scope) and tree==expected:roots.append((function,demangling))
        if len(roots)!=1:raise ValueError('Missing/ambiguous private registry once initializer')
        function,demangling=roots[0];sil.instructions(function)
        if function.header!=f'sil private [global_init_once_fn] [ossa] @{function.symbol} : $@convention(c) (Builtin.RawPointer) -> () {{':
            raise ValueError('Registry initializer signature/attributes differ')
        global_symbol=function.symbol[:-3]+'SDySSSaySSGGvp'
        actual=sil.transfer_program(function.lines,'NO_CALLBACK_ALLOWED')
        grammar=sil.transfer_program(expected_program(registry,global_symbol),'NO_CALLBACK_ALLOWED')
        if actual!=grammar:raise ValueError('Complete registry allocation/index/store/literal/ownership program differs')
        result.update(privateRegistryConstructionProgramProved=True,symbol=function.symbol,privateGlobal=global_symbol,
            compilerDemangling=demangling,checkedInstructions=len(actual),programSHA256=cert.coverage.digest(actual),
            sourceRegistrySHA256=cert.coverage.digest(registry),siteKeys=len(registry),
            constructedArrays=1+len(registry),literalStrings=len(registry)+sum(map(len,registry.values())),
            conclusion='Complete acyclic initializer constructs only the exact registry data in fresh bounded private arrays and writes the final Dictionary only to its own private global',
            trustedContracts=['Bound Swift Array allocation/storage/finalization ABI',
                'builtin String literal construction and Dictionary<String, Array<String>> literal initialization',
                'normal resource behavior and Swift global-once execution'])
    except (ValueError,KeyError,IndexError,OSError,subprocess.SubprocessError) as error:result['failures'].append(str(error))
    result['remainingObligations']=['actual SDK/runtime library contract agreement and resource/trap behavior',
        'registry accessor, recorder mutations, synchronization and complete observer noninterference',
        'all concrete site ownership/evaluation equivalence, independent review and actual runtime outcomes']
    return result
