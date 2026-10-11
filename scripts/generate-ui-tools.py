"""Inventory-only tool models. The first-person left hand uses the proven shield path."""
from pathlib import Path
import json
import math

root = Path(__file__).resolve().parent.parent
pack = root / 'resource-pack'
icons = json.loads((root / 'src/main/resources/icons.json').read_text())
tools = sorted(k for k in icons if k.endswith(('_sword', '_axe', '_pickaxe', '_shovel', '_hoe')))

def write(path, data):
    p = pack / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(data, indent=2) + '\n')

def description(identifier):
    return {'identifier': identifier, 'texture_width': 16, 'texture_height': 16,
            'visible_bounds_width': 4, 'visible_bounds_height': 4,
            'visible_bounds_offset': [0, 1, 0]}

# Each voxel samples one native texture pixel on every face. Transparent pixels
# are discarded by the same alpha-test material as the vanilla shield.
# This keeps native textures and pixel depth without texture_mesh orientation.
# Vanilla Java item/handheld firstperson_lefthand uses scale 0.68.
# Its displayed texture basis, converted to the Bedrock preview camera facing
# +Z, is Rx(25) Ry(90). Bedrock import reverses voxel X, so also rotate
# the cube basis by 180 degrees around Y (symmetric depth). Solve a child rotation.
def multiply(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]
x, y, z = map(math.radians, [-1.0, -176.0, -2.5])
rx = [[1, 0, 0], [0, math.cos(x), -math.sin(x)], [0, math.sin(x), math.cos(x)]]
ry = [[math.cos(y), 0, math.sin(y)], [0, 1, 0], [-math.sin(y), 0, math.cos(y)]]
rz = [[math.cos(z), -math.sin(z), 0], [math.sin(z), math.cos(z), 0], [0, 0, 1]]
shield = multiply(multiply(rz, ry), rx)
angle = math.radians(25)
target = [[0, 0, -1], [-math.sin(angle), math.cos(angle), 0], [math.cos(angle), math.sin(angle), 0]]
local = multiply([list(row) for row in zip(*shield)], target)
child_rotation = [round(-math.degrees(math.atan2(local[2][1], local[2][2])), 8),
                  round(-math.degrees(math.asin(-local[2][0])), 8),
                  round(math.degrees(math.atan2(local[1][0], local[0][0])), 8)]
pixel = 0.68
center = [1, 36, -0.125]
cubes = []
for y in range(16):
    for x in range(16):
        cubes.append({'origin': [round(center[0] + (x - 8) * pixel, 8),
                                  round(center[1] + (7 - y) * pixel, 8),
                                  round(center[2] - pixel / 2, 8)],
                      'size': [pixel, pixel, pixel],
                      'uv': {face: {'uv': [x, y], 'uv_size': [1, 1]}
                             for face in ['north', 'south', 'east', 'west', 'up', 'down']}})
models = [{'description': description('geometry.nativeoffhand.ui_tool.first_left'),
           'bones': [{'name': 'shield', 'binding': 'q.item_slot_to_bone_name(c.item_slot)',
                      'pivot': [1, 15.5, 3]},
                     {'name': 'nativeoffhand_sprite', 'parent': 'shield',
                      'pivot': center, 'rotation': child_rotation, 'cubes': cubes}]}]
# Keep the previous main-hand and third-person geometry. Only first-person
# offhand changes to cubes. All bones use the name targeted by shield animations.
for perspective, hand in [('first', 'right'), ('third', 'right'), ('third', 'left')]:
    first, off = perspective == 'first', hand == 'left'
    position = [2.01, -0.5, -1] if first else [-1, -1, -9]
    if off:
        position[0] *= -1
    mesh = {'texture': 'default', 'local_pivot': [9, 0, 6] if first else [6, 0, 7],
            'position': position, 'rotation': [0, -135, 90] if first else [0, 30, -90],
            'use_pixel_depth': True, 'scale': [1, 1, 1]}
    models.append({'description': description('geometry.nativeoffhand.ui_tool.' + perspective + '_' + hand),
                   'bones': [{'name': 'shield', 'binding': 'q.item_slot_to_bone_name(c.item_slot)',
                              'pivot': [0, 0, 0], 'texture_meshes': [mesh]}]})
write('models/entity/nativeoffhand_ui_tool.geo.json',
      {'format_version': '1.16.0', 'minecraft:geometry': models})

# Native shield animation controller selects explicit first-person animations.
# Third-person retains the previous tool transform rather than the shield pose.
write('animations/nativeoffhand_ui_tool.animation.json',
      {'format_version': '1.10.0', 'animations': {
          'animation.nativeoffhand.ui_tool.third_person': {'loop': True, 'bones': {'shield': {
              'position': ["c.item_slot == 'main_hand' ? 0.5 : -0.5", 21.5, 1.0],
              'rotation': [0, 0, 0]}}}}})
controllers = {}
for view in ['first_right', 'third_right', 'third_left']:
    controllers['controller.render.nativeoffhand.ui_tool.' + view] = {
        'geometry': 'Geometry.' + view,
        'materials': [{'*': 'q.is_enchanted ? Material.enchanted : Material.default'}],
        'textures': ['Texture.default', 'Texture.enchanted']}
write('render_controllers/nativeoffhand_ui_tool.render_controllers.json',
      {'format_version': '1.10.0', 'render_controllers': controllers})

# Keep the proven shield rotation and vanilla-oriented child geometry.
# Only first-person offhand translation is adjusted from the user's paired
# normal/inventory screenshots; see docs/ui-tool-attachables.md for calibration.
poses = {
    'main_hand_first_person_pos_x': -5.5, 'main_hand_first_person_pos_y': 21.0,
    'main_hand_first_person_pos_z': -3.0, 'main_hand_first_person_rot_x': 38.0,
    'main_hand_first_person_rot_y': -120.0, 'main_hand_first_person_rot_z': -63.0,
    'off_hand_first_person_pos_x': -14.4, 'off_hand_first_person_pos_y': -3.99,
    'off_hand_first_person_pos_z': 3.43, 'off_hand_first_person_with_bow_pos_z': -25.0,
    'off_hand_first_person_rot_x': 1.0, 'off_hand_first_person_rot_y': 176.0,
    'off_hand_first_person_rot_z': -2.5}
for name in tools:
    icon = icons[name]
    texture = icon if isinstance(icon, str) else icon['textures']
    identifier = 'nativeoffhand_ui:' + name
    write('attachables/nativeoffhand_ui/' + name + '.json', {
        'format_version': '1.10.0', 'minecraft:attachable': {'description': {
            'identifier': identifier,
            'item': {identifier: "query.owner_identifier == 'minecraft:player'"},
            'materials': {'default': 'entity_alphatest', 'enchanted': 'entity_alphatest_glint'},
            'textures': {'default': texture, 'enchanted': 'textures/misc/enchanted_item_glint'},
            'geometry': {'default': 'geometry.nativeoffhand.ui_tool.first_left',
                         **{v: 'geometry.nativeoffhand.ui_tool.' + v
                            for v in ['first_right', 'third_right', 'third_left']}},
            'animations': {
                'wield': 'controller.animation.shield.wield',
                'wield_main_hand_first_person': 'animation.shield.wield_main_hand_first_person',
                'wield_off_hand_first_person': 'animation.shield.wield_off_hand_first_person',
                'wield_first_person_block': 'animation.shield.wield_first_person_blocking',
                'wield_main_hand_first_person_block': 'animation.shield.wield_main_hand_first_person_blocking',
                'wield_off_hand_first_person_block': 'animation.shield.wield_off_hand_first_person_blocking',
                'wield_third_person': 'animation.nativeoffhand.ui_tool.third_person'},
            'scripts': {
                'initialize': ['variable.' + k + ' = ' + str(v) + ';' for k, v in poses.items()],
                'pre_animation': [
                    "variable.is_blocking_main_hand = query.blocking && !query.is_item_name_any('slot.weapon.offhand', 'minecraft:shield') && query.is_item_name_any('slot.weapon.mainhand', 'minecraft:shield');",
                    "variable.is_blocking_off_hand = query.blocking && query.is_item_name_any('slot.weapon.offhand', 'minecraft:shield');",
                    "variable.is_using_bow = (query.get_equipped_item_name == 'bow') && (query.main_hand_item_use_duration > 0.0f);"],
                'animate': ['wield']},
            'render_controllers': [
                {'controller.render.item_default': "c.is_first_person && c.item_slot != 'main_hand'"},
                {'controller.render.nativeoffhand.ui_tool.first_right': "c.is_first_person && c.item_slot == 'main_hand'"},
                {'controller.render.nativeoffhand.ui_tool.third_right': "!c.is_first_person && c.item_slot == 'main_hand'"},
                {'controller.render.nativeoffhand.ui_tool.third_left': "!c.is_first_person && c.item_slot != 'main_hand'"}]}}})
print('Generated', len(tools), 'inventory-only tools with shield-bound left-hand cubes.')
