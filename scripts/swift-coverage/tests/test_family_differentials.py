"""Finite family controls: actual original/transformed programs, no invented hits."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

TOOL=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('family_fixture_runner',TOOL / 'tests/test_tool.py')
fixtures=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixtures)

class FamilyDifferentials(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root=Path(tempfile.mkdtemp(prefix='swift-family-differentials-',dir=fixtures.coverage.ROOT / '.local-private'))
        print('Finite family differential evidence:',cls.root)
    compare_fixture=fixtures.ToolTests.compare_fixture

    def test_standard_sequence_shapes_nested_nil_and_iterator_destruction(self):
        manifest,_=self.compare_fixture('sequences','''
var events:[String]=[]
struct Rows:Sequence {
 let values:[Int?]
 final class Iterator:IteratorProtocol {
  let values:[Int?];var offset=0
  init(_ values:[Int?]) {self.values=values;events.append("iterator")}
  deinit {events.append("destroy")}
  func next()->Int?? {events.append("next");guard offset<values.count else{return nil};defer{offset += 1};return .some(values[offset])}
 }
 func makeIterator()->Iterator {events.append("make");return Iterator(values)}
}
enum Stop:Error {case requested}
func exercise(_ values:[Int]) throws -> Int {
 var total=0
 for n in values where n >= 0 {defer{events.append("defer")};if n==0 {continue};if n==2 {break};total += n}
 for n in values {if n==3 {throw Stop.requested};if n==4 {return total};total += n}
 for n in Int32(0)..<Int32(2) {total += Int(n)}
 for (i,n) in values.enumerated() {total += i+n}
 for i in values.indices {total += i}
 for n in values.reversed() {total += n}
 for char in "aЖ" {total += String(char).utf8.count}
 for scalar in "aЖ".unicodeScalars {total += Int(scalar.value)}
 for value in Rows(values:[nil,1,nil]) {events.append(String(describing:value))}
 for value in Rows(values:[1,2]) {events.append(String(describing:value));break}
 return total
}
''','for values in [[],[-1,0,1,2],[3],[4]] {do {print(try exercise(values))} catch {print("caught")}};print(events.joined(separator:","))')
        self.assertEqual(manifest['sources'][0]['unsupported'],[])
        self.assertGreaterEqual(sum(s['kind']=='iterator-next' for s in manifest['sources'][0]['sites']),10)

    def test_six_current_weak_context_shapes_any_optional_try_and_availability_cleanup(self):
        manifest,_=self.compare_fixture('weak-errors-availability','''
import Foundation
var events:[String]=[]
enum Failure:Error {case requested}
final class Owner {
 deinit {events.append("deinit")}
 func request(_ label:String) {events.append(label)}
 func finish(_ failure:Failure) {events.append("finish")}
 func first()->()->Void {{[weak self] in guard let self else {return};self.request("first")}}
 func second()->()->Void {{[weak self] in guard let self else {events.append("leave");return};self.request("second")}}
 func third(_ id:String)->()->Void {{[weak self] in guard let self else {return};self.request(id)}}
 func fourth()->()->Void {{[weak self] in self?.request("fourth")}}
 func sixth()->()->Void {{[weak self] in self?.finish(.requested)}}
}
final class Holder {weak var module:Owner?;init(_ module:Owner){self.module=module}}
func weakExercise() {
 var owner:Owner?=Owner()
 let holder=Holder(owner!)
 let callbacks=[owner!.first(),owner!.second(),owner!.third("third"),owner!.fourth(),owner!.sixth()]
 for callback in callbacks {callback()}
 holder.module?.request("holder")
 owner=nil
 for callback in callbacks {callback()}
 holder.module?.request("gone")
}
func maybe(_ mode:Int)throws->Int? {defer{events.append("maybe-defer")};if mode==1 {throw Failure.requested};return mode==0 ? nil:7}
func typedAny(_ data:Data)->Int {
 let objects=(try? JSONSerialization.jsonObject(with:data)) as? [[String:Any]] ?? []
 let object=(try? JSONSerialization.jsonObject(with:data)) as? [String:Any] ?? [:]
 return objects.count+object.count
}
func available(_ mode:Int)throws->Int {
 defer{events.append("available-defer")}
 if #available(macOS 99,*),mode==0 {return 99}
 if #available(macOS 10.15,*),mode==1 {throw Failure.requested}
 if #available(macOS 10.15,*),mode==2 {return 2}
 return 0
}
''','weakExercise();for mode in [0,1,2] {print(try? maybe(mode));do {print(try available(mode))} catch {print("caught")}};for text in ["[]","{}","not JSON"] {print(typedAny(Data(text.utf8)))};print(events.joined(separator:","))')
        source=manifest['sources'][0]
        self.assertEqual(source['unsupported'],[])
        self.assertEqual(sum(c['sourceText']=='weak' for c in source['classificationInventory']),6)
        self.assertEqual(len(source['configuration']['typingHints']),2)
        self.assertEqual(sum(s['kind']=='availability' for s in source['sites']),3)
