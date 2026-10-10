from pathlib import Path
import json
root = Path(__file__).resolve().parent.parent
pack = root / 'resource-pack'
icons = json.loads((root / 'src/main/resources/icons.json').read_text())
tools = sorted(k for k in icons if k.endswith(('_sword', '_axe', '_pickaxe', '_shovel', '_hoe')))
def write(path, data):
    p = pack / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(data, indent=2) + '\n')
models = []
for perspective in ['first', 'third']:
    for hand in ['right', 'left']:
        off = hand == 'left'
        first = perspective == 'first'
        position = [2.01, -0.5, -1] if first else [-1, -1, -9]
        rotation = [0, -135, 90] if first else [0, 30, -90]
        local_pivot = [9, 0, 6] if first else [6, 0, 7]
        if off:
            position[0] *= -1
            local_pivot[0] *= -1
            rotation[1] *= -1
            rotation[2] *= -1
        mesh = {'texture': 'default', 'local_pivot': local_pivot,
                'position': position, 'rotation': rotation, 'use_pixel_depth': True,
                # Texture meshes occupy X/Z with Y as extrusion depth. Reflect
                # texture X and depth Y together, keeping determinant positive.
                'scale': [-1, -1, 1] if off else [1, 1, 1]}
        models.append({'description': {'identifier': 'geometry.nativeoffhand.ui_tool.' + perspective + '_' + hand,
                          'texture_width': 16, 'texture_height': 16,
                          'visible_bounds_width': 4, 'visible_bounds_height': 4, 'visible_bounds_offset': [0, 1, 0]},
                       'bones': [{'name': 'nativeoffhand_tool', 'binding': 'q.item_slot_to_bone_name(c.item_slot)',
                                  'pivot': [0, 0, 0], 'texture_meshes': [mesh]}]})
write('models/entity/nativeoffhand_ui_tool.geo.json', {'format_version': '1.16.0', 'minecraft:geometry': models})
write('animations/nativeoffhand_ui_tool.animation.json', {'format_version': '1.10.0', 'animations': {
    'animation.nativeoffhand.ui_tool.hold': {'loop': True, 'bones': {'nativeoffhand_tool': {
        # Bound item bones require the 24-unit origin correction also seen in
        # vanilla spyglass holding. Wield transforms otherwise follow vanilla.
        'position': ["c.is_first_person ? (c.item_slot == 'off_hand' ? 5.5 : -5.5) : (c.item_slot == 'off_hand' ? -0.5 : 0.5)",
                     'c.is_first_person ? 21.0 : 21.5', 'c.is_first_person ? -3.0 : 1.0'],
        'rotation': ['c.is_first_person ? 38.0 : 0.0',
                     "c.is_first_person ? (c.item_slot == 'off_hand' ? 120.0 : -120.0) : 0.0",
                     "c.is_first_person ? (c.item_slot == 'off_hand' ? 63.0 : -63.0) : 0.0"]}}}}})
write('render_controllers/nativeoffhand_ui_tool.render_controllers.json', {'format_version': '1.10.0', 'render_controllers': {
    'controller.render.nativeoffhand.ui_tool': {
        'geometry': "c.is_first_person ? (c.item_slot == 'off_hand' ? Geometry.first_left : Geometry.first_right) : (c.item_slot == 'off_hand' ? Geometry.third_left : Geometry.third_right)",
        'materials': [{'*': 'q.is_enchanted ? Material.enchanted : Material.default'}],
        'textures': ['Texture.default', 'Texture.enchanted']}}})
for name in tools:
    icon = icons[name]
    texture = icon if isinstance(icon, str) else icon['textures']
    write('attachables/nativeoffhand_ui/' + name + '.json', {'format_version': '1.10.0', 'minecraft:attachable': {'description': {
        'identifier': 'nativeoffhand_ui:' + name,
        'materials': {'default': 'entity_alphatest', 'enchanted': 'entity_alphatest_glint'},
        'textures': {'default': texture, 'enchanted': 'textures/misc/enchanted_item_glint'},
        'geometry': {perspective + '_' + hand: 'geometry.nativeoffhand.ui_tool.' + perspective + '_' + hand
                     for perspective in ['first', 'third'] for hand in ['left', 'right']},
        'animations': {'hold': 'animation.nativeoffhand.ui_tool.hold'},
        'scripts': {'animate': ['hold']},
        'render_controllers': ['controller.render.nativeoffhand.ui_tool']}}})
print('Generated', len(tools), 'inventory-only sprite tool attachables and four slot/perspective geometries.')
